library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// =====================================================================================
// Dynamic work-queue MTR pipeline
// -------------------------------------------------------------------------------------
// Unlike jenkins/pipeline-parallel-mtr.groovy, which splits all MTR suites into 8
// hand-tuned static groups (set_suites() in jenkins/suites-groups.sh), this pipeline
// keeps the COMPLETE list of suites in a single shared queue on the Jenkins controller
// and lets N worker agents pull one suite at a time, run it, and request the next until
// the queue is drained. This is work-stealing / dynamic load balancing: no static split
// to maintain and no tail-latency from a worker that drew the heavy suites.
//
// How the "coordinator" works without external infrastructure:
//   - The pipeline script (and every parallel branch closure) runs in the SAME JVM on
//     the Jenkins controller; only sh/node steps run on agents. A single shared object
//     on the heap is therefore a genuine cross-branch queue.
//   - Suites are handed out via an integer cursor into an immutable list; the failed/
//     running collections are updated by reassignment, not mutation. Pops happen inside
//     @NonCPS, step-free helpers. CPS orchestration is single-threaded and @NonCPS calls
//     run to completion without yielding, so this is atomic. (We avoid java.util.concurrent.*,
//     Collection mutators, and synchronized blocks: the first two are rejected by the
//     script-security sandbox, the last breaks CPS pipeline serialization.)
// =====================================================================================

// Cache configuration constants (see pipeline-parallel-mtr.groovy / PKG-769 for rationale)
final String CCACHE_MAXSIZE = '8G'
final int CACHE_RETENTION_DAYS_NORMAL = 60
final int CACHE_RETENTION_DAYS_SANITIZER = 120

PIPELINE_TIMEOUT = 24
MAX_S3_RETRIES = 12
S3_ROOT_DIR = 's3://ps-build-cache'
BUILD_NUMBER_BINARIES_FOR_RERUN = 0
BUILD_TRIGGER_BY = ''
WORK_DIR = 'work'
SERVER_VERSION = '1.0.0'

LABEL = ''
MICRO_LABEL = ''
CLOUD_CHOSEN = ''

// ----- Dynamic work-queue shared state (lives on the controller, shared by all branches)
// The Jenkins script-security sandbox rejects java.util.concurrent.* and the Collection
// MUTATOR methods (clear/add/remove/addAll/put). So instead of mutating shared collections
// we:
//   - hand out suites via an integer cursor (NEXT_INDEX) into an immutable list ALL_SUITES;
//   - update FAILED_SUITES / RUNNING_SUITES by REASSIGNING the field with whitelisted GDK
//     operators ('+', findAll, collect).
// Thread safety comes from the CPS model: all Groovy orchestration (including every pop)
// runs on a single cooperative thread, and the @NonCPS helpers below run to completion
// without yielding, so reads + reassignments are effectively atomic.
ALL_SUITES     = []   // full ordered suite list (set once)
NEXT_INDEX     = 0    // cursor: index of the next suite to hand out
FAILED_SUITES  = []   // suites/specials to rerun
RUNNING_SUITES = [:]  // suite -> "worker-N"
SUITE_RESULTS  = []   // per-suite timing for the end-of-run summary: [suite,worker,seq,secs,status]

// Heavy suites are split into separate "|nobig" and "|big" queue items so the two halves
// can run on different workers (a bare suite would run both back-to-back on one worker).
// Ranked heaviest-first from the PS80 Valgrind walltimes
// (jenkins/gen-suites-groups/PS80-valgrind*.txt). Note the real MTR suite name for the
// engines funcs suite is "engines/funcs" (slash), which is what get_default_suites_80
// returns. Light suites not listed here stay whole (one queue item, both halves together).
HEAVY_SUITES = ['innodb', 'main', 'group_replication', 'rpl', 'clone', 'rpl_gtid',
                'engines/funcs', 'rocksdb', 'x', 'rpl_nogtid', 'innodb_undo',
                'component_keyring_file']

// @NonCPS helpers: pure data manipulation, NO pipeline steps (echo/sh/env/node) inside,
// and NO Collection mutator calls (see note above).
@NonCPS
String nextSuite() {
    if (NEXT_INDEX >= ALL_SUITES.size()) {
        return null   // null == queue drained
    }
    String s = ALL_SUITES[NEXT_INDEX]
    NEXT_INDEX = NEXT_INDEX + 1
    return s
}

@NonCPS
int queueSize() {
    int remaining = ALL_SUITES.size() - NEXT_INDEX
    return remaining < 0 ? 0 : remaining
}

@NonCPS
void loadQueue(List items) {
    ALL_SUITES = items
    NEXT_INDEX = 0
}

@NonCPS
void recordFailedSuite(String suite) {
    FAILED_SUITES = FAILED_SUITES + [suite]
}

@NonCPS
void markRunning(String suite, String owner) {
    RUNNING_SUITES = RUNNING_SUITES + [(suite): owner]
}

@NonCPS
void markDone(String suite) {
    RUNNING_SUITES = RUNNING_SUITES.findAll { k, v -> k != suite }
}

// Move any still-"running" suites owned by this worker into the failed list. Called from
// the worker's finally so an interrupted/aborted in-flight suite is rerun, not lost.
@NonCPS
void sweepRunningToFailed(String owner) {
    def stuck = RUNNING_SUITES.findAll { k, v -> v == owner }.collect { k, v -> k }
    FAILED_SUITES  = FAILED_SUITES + stuck
    RUNNING_SUITES = RUNNING_SUITES.findAll { k, v -> v != owner }
}

// Record one suite's wall-clock result for the end-of-run summary (reassignment, no mutators).
@NonCPS
void recordSuiteResult(String suite, int worker, int seq, long secs, String status) {
    SUITE_RESULTS = SUITE_RESULTS + [[suite: suite, worker: worker, seq: seq, secs: secs, status: status]]
}

// Right-pad a string to width n using only whitelisted String ops (no String.format).
@NonCPS
String pad(String s, int n) {
    def out = s
    while (out.length() < n) {
        out = out + ' '
    }
    return out
}

// Render the suite -> worker -> duration table (longest first) plus per-worker totals.
// Returns a plain string; the caller echoes/writes it (no pipeline steps in @NonCPS).
@NonCPS
String renderRunSummary() {
    if (SUITE_RESULTS.isEmpty()) {
        return 'MTR dynamic run summary: no suites were run.'
    }
    def rows = ([] + SUITE_RESULTS).sort { a, b -> b.secs <=> a.secs }   // copy, then longest-first
    def lines = []
    lines += ['==== MTR dynamic run summary (longest first) ====']
    lines += [pad('SUITE', 38) + pad('WORKER', 8) + pad('SEQ', 6) + pad('SECONDS', 10) + 'STATUS']
    long total = 0
    def byWorker = [:]
    rows.each { r ->
        total += r.secs
        byWorker = byWorker + [(r.worker): ((byWorker[r.worker] ?: 0) + r.secs)]
        lines += [pad(r.suite, 38) + pad('w' + r.worker, 8) + pad('' + r.seq, 6) + pad('' + r.secs, 10) + r.status]
    }
    lines += ['---- per-worker busy time (seconds) ----']
    byWorker.each { w, secs -> lines += [pad('worker ' + w, 38) + secs] }
    lines += ["total suite-seconds: ${total}  (items: ${rows.size()})"]
    return lines.join('\n')
}

// De-duplicate preserving order, using only whitelisted ops (contains + reassignment).
@NonCPS
List dedup(List items) {
    def out = []
    for (it in items) {
        if (!out.contains(it)) {
            out = out + [it]
        }
    }
    return out
}

// Expand heavy suites into "|big" + "|nobig" items and order the queue for good makespan:
// all heavy "|big" halves first (the longest poles), then heavy "|nobig" halves, then the
// remaining light suites whole. "present" keeps the HEAVY_SUITES ranking order.
// bigTest/onlyBig come from MTR_ARGS (read by the CPS caller; env is not available here).
// Splitting is only valid when the run actually executes both halves: the runner puts a
// "<suite>|big" item in suitesBig, which it runs only under --big-test/--only-big-test.
// Without that flag the invocation runs no suite at all, MTR prints no "Logging:" line, and
// the wrapper's walltime grep then fails the step under "set -eo pipefail" - so every |big
// item became an infra-fail and three of them tripped each worker's circuit breaker, with the
// |big halves leading the queue.
@NonCPS
List expandAndOrder(List items, boolean bigTest = true, boolean onlyBig = false) {
    def uniq    = dedup(items)
    def present = HEAVY_SUITES.findAll { uniq.contains(it) }       // heavy suites actually in the list, ranked
    def bigItems   = (bigTest || onlyBig) ? present.collect { it + '|big' } : []
    // With --only-big-test there is no nobig half to run; with neither flag the suite is one
    // undivided item (the runner then runs just the normal half).
    def nobigItems = onlyBig ? [] : present.collect { it + (bigTest ? '|nobig' : '') }
    def light      = uniq.findAll { !HEAVY_SUITES.contains(it) }
    return bigItems + nobigItems + light
}

// Remove already-completed items (from a resumed build's checkpoint), preserving order.
@NonCPS
List subtractCompleted(List items, List done) {
    return items.findAll { !done.contains(it) }
}

// functions start here
void syncDirToS3(String SRC_DIRECTORY, String DST_DIRECTORY, String EXCLUDE_PATTERN) {
    echo "Sync ${SRC_DIRECTORY} directory to S3 ${S3_ROOT_DIR}/${DST_DIRECTORY}. Exclude: ${EXCLUDE_PATTERN}. Max retries: ${MAX_S3_RETRIES}"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            retry=0
            S3_PATH=${S3_ROOT_DIR}/${DST_DIRECTORY}/
            until [ \$retry -eq ${MAX_S3_RETRIES} ] || aws s3 sync --no-progress --acl public-read --exclude '${EXCLUDE_PATTERN}' ${SRC_DIRECTORY} \$S3_PATH; do
                sleep 5
                retry=\$((retry+1))
            done
        """
    }
}

void uploadFileToS3(String SRC_FILE_PATH, String DST_DIRECTORY, String DST_FILE_NAME) {
    echo "Upload ${SRC_FILE_PATH} file to S3 ${S3_ROOT_DIR}/${DST_DIRECTORY}/${DST_FILE_NAME}. Max retries: ${MAX_S3_RETRIES}"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            retry=0
            S3_PATH=${S3_ROOT_DIR}/${DST_DIRECTORY}/${DST_FILE_NAME}
            until [ \$retry -eq ${MAX_S3_RETRIES} ] || aws s3 cp --no-progress --acl public-read ${SRC_FILE_PATH} \$S3_PATH; do
                sleep 5
                retry=\$((retry+1))
            done
        """
    }
}

void downloadFileFromS3(String SRC_DIRECTORY, String SRC_FILE_NAME, String DST_PATH) {
    echo "Downloading ${S3_ROOT_DIR}/${SRC_DIRECTORY}/${SRC_FILE_NAME} from S3 to ${DST_PATH} . Max retries: ${MAX_S3_RETRIES}"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            retry=0
            S3_PATH=${S3_ROOT_DIR}/${SRC_DIRECTORY}/${SRC_FILE_NAME}
            until [ \$retry -eq ${MAX_S3_RETRIES} ] || aws s3 cp --no-progress \$S3_PATH ${DST_PATH}; do
                sleep 5
                retry=\$((retry+1))
            done
        """
    }
}

void downloadFilesForTests() {
    downloadFileFromS3("${BUILD_TAG_BINARIES}", 'binary.tar.gz', "./${WORK_DIR}/binary.tar.gz")
}

void prepareWorkspace(Integer WORKER_ID, boolean UNIT_TESTS) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """#!/bin/bash
            echo "prepareWorkspace for MTR worker ${WORKER_ID}"
            whoami
            ls -l

            sudo git log --oneline -10
            sudo git reset --hard

            wipe_work_dir() {
                # "git clean" doesn't remove "sources/" because of ".gitignore"
                sudo git clean -xdf || :
                sudo rm -rf sources

                # Ensure WORK_DIR is really clean (helps with re-runs / retries).
                # "git clean" may leave parts of work/extract behind (e.g. "Directory not empty")
                sudo rm -rf ${WORK_DIR}
            }

            # "sources" + "work/build" required for running unit tests are valid only for the
            # primary worker (WORKER #1) but not for retry/re-runs
            if [ "$WORKER_ID" = "1" ] && [ "$UNIT_TESTS" != "false" ]; then
                if [ -d sources ]; then
                    sudo cat sources/MYSQL_VERSION || :
                    if ! sudo git -C sources status >/dev/null 2>&1; then
                        echo "Warning: Git repo in ./sources is broken, removing it. It should happen only for re-runs."
                        sudo GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git -C sources -c safe.directory="$WORKSPACE/sources" log --oneline -10 || :
                        wipe_work_dir
                    else
                        sudo git -C sources log --oneline -10
                        sudo git -C sources reset --hard
                        sudo git -C sources clean -xdf -e . || :
                        sudo git -C sources submodule update --init || :
                        sudo rm -rf ${WORK_DIR}/extract ${WORK_DIR}/results ${WORK_DIR}/walltimes || :
                        sudo rm -f  ${WORK_DIR}/binary.tar.gz ${WORK_DIR}/mtr-test_*.log || :
                    fi
                else
                    echo "Warning: Missing 'sources' for MTR worker ${WORKER_ID}. It should happen only for re-runs."
                    wipe_work_dir
                fi
            else
                wipe_work_dir
            fi

            # import apt_get_retry(), yum_retry() from utils.inc.sh
            source ./local/utils.inc.sh

            # For LABEL host jq is required by keyring_vault; zstd is required for valgrind runs
            if [ -f /usr/bin/yum ]; then
                yum_retry makecache
                yum_retry -y install jq zstd
            else
                apt_get_retry update
                apt_get_retry install -y jq zstd
            fi
        """
    }
}

void cleanWorkspace(Integer WORKER_ID) {
    echo "[INFO] Worker ${WORKER_ID}: cleaning up workspace."
    sh """
        # "git clean" doesn't remove "sources/" because of ".gitignore"
        sudo git clean -xdf || :
        sudo rm -rf sources

        # Ensure WORK_DIR is really clean (helps with re-runs / retries).
        sudo rm -rf ${WORK_DIR}
    """
}

// Log in to ECR and pull the build image ONCE per worker. Each suite then runs as a
// separate "docker run", but because the image is already cached locally those runs need
// no further ECR login (the public token would otherwise be fetched per suite). Public
// ECR tokens expire after ~12h, but that is irrelevant here: a cached local image is used
// regardless of token state, so even multi-hour valgrind drains are fine.
void dockerEcrLogin() {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """#!/bin/bash
            set -o errexit
            ARCH_SUFFIX=""
            if [[ \$(uname -m) == "aarch64" ]]; then ARCH_SUFFIX="-aarch64"; fi
            SOURCE_IMAGE=\$(echo "${DOCKER_OS}" | tr ':' '-')
            IMAGE="public.ecr.aws/e7j3v3n0/ps-build:\${SOURCE_IMAGE}\${ARCH_SUFFIX}"
            echo "ECR login + image cache (once per worker): \$IMAGE"
            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
            sg docker -c "docker pull \$IMAGE"
        """
    }
}

// Run one MTR invocation inside the build docker image. SUITES is normally a SINGLE suite
// token pulled from the queue; for the primary worker's special pass it is empty and only
// the unit/CIFS/keyring-vault/ps-protocol bits run. MTR_RUN_TAG makes per-suite output file
// names unique so many suites on one node don't overwrite each other.
void doTests(String WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean PS_PROTOCOL_TESTS = false, String MTR_RUN_TAG = '') {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withCredentials([
            string(credentialsId: VAULT_V1_DEV_ROOT_TOKEN, variable: VAULT_V1_DEV_ROOT_TOKEN),
            string(credentialsId: VAULT_V2_DEV_ROOT_TOKEN, variable: VAULT_V2_DEV_ROOT_TOKEN)]) {
            sh """#!/bin/bash
                echo "Starting MTR worker ${WORKER_ID}, RUN_TAG: ${MTR_RUN_TAG}, SUITES: ${SUITES}, STANDALONE_TESTS: ${STANDALONE_TESTS}, UNIT_TESTS: ${UNIT_TESTS}, CIFS_TESTS: ${CIFS_TESTS}, KV_TESTS: ${KV_TESTS}, PS_PROTOCOL_TESTS: ${PS_PROTOCOL_TESTS}"

                if [[ "${CIFS_TESTS}" == "true" ]]; then
                    echo "Preparing filesystem for CIFS tests"

                    if [ -f /usr/bin/yum ]; then
                        sudo yum -y install dosfstools
                    else
                        sudo apt-get install -y dosfstools
                    fi

                    if [[ ! -f /mnt/ci_disk_${CMAKE_BUILD_TYPE}.img ]] && [[ -z \$(mount | grep /mnt/ci_disk_dir_${CMAKE_BUILD_TYPE}) ]]; then
                        sudo dd if=/dev/zero of=/mnt/ci_disk_${CMAKE_BUILD_TYPE}.img bs=1G count=10
                        sudo /sbin/mkfs.vfat /mnt/ci_disk_${CMAKE_BUILD_TYPE}.img
                        sudo mkdir -p /mnt/ci_disk_dir_${CMAKE_BUILD_TYPE}
                        sudo mount -o loop -o uid=1001 -o gid=1001 -o check=r /mnt/ci_disk_${CMAKE_BUILD_TYPE}.img /mnt/ci_disk_dir_${CMAKE_BUILD_TYPE}
                    fi
                fi

                if [[ "${UNIT_TESTS}" == "false" ]]; then
                    echo "Disabling unit tests"
                    MTR_ARGS=\${MTR_ARGS//"--unit-tests-report"/""}
                fi
                if [[ "${CIFS_TESTS}" == "false" ]]; then
                    echo "Disabling CIFS mtr"
                    CI_FS_MTR=no
                else
                    echo "Enabling CIFS mtr"
                    CI_FS_MTR=yes
                fi
                if [[ "${PS_PROTOCOL_TESTS}" == "false" ]]; then
                    echo "Disabling PS_PROTOCOL mtr"
                    WITH_PS_PROTOCOL=no
                else
                    echo "Enabling PS_PROTOCOL mtr"
                    WITH_PS_PROTOCOL=yes
                fi
                if [[ "${KV_TESTS}" == "false" ]]; then
                    echo "Disabling Keyring Vault mtr"
                    KEYRING_VAULT_MTR=no
                else
                    echo "Enabling Keyring Vault mtr"
                    KEYRING_VAULT_MTR=yes
                fi

                # NOTE: as in pipeline-parallel-mtr.groovy, this is passed through but no runner
                # script reads MTR_STANDALONE_TESTS yet, so named standalone tests are not run by
                # either pipeline. Kept identical to the static pipeline so both start working the
                # day the runner grows support, rather than silently diverging here.
                MTR_STANDALONE_TESTS="${STANDALONE_TESTS}"
                export MTR_SUITES="${SUITES}"
                export SERVER_VERSION="${SERVER_VERSION}"

                # Dynamic-pipeline knobs (consumed by docker/run-test-parallel-mtr and
                # local/test-binary-parallel-mtr):
                #   MTR_RUN_TAG          - unique per-invocation label for output file names
                #   REUSE_EXTRACT        - reuse the extracted binary tree across suites
                #   SKIP_RESULTS_TARBALL - mtr_var is tarred once at worker end, not per suite
                export MTR_RUN_TAG="${MTR_RUN_TAG}"
                export REUSE_EXTRACT=yes
                export SKIP_RESULTS_TARBALL=yes

                # NOTE: no "docker login" here. dockerEcrLogin() already logged in and pulled
                # the image once for this worker, so each per-suite "docker run" reuses the
                # locally-cached image with no extra ECR round-trip.
                sg docker -c "
                    if [ \$(docker ps -a -q | wc -l) -ne 0 ]; then
                        docker ps -q | xargs docker stop --time 1 || :
                        docker rm --force consul vault-prod-v{1..2} vault-dev-v{1..2} || :
                    fi
                    ./docker/run-test-parallel-mtr ${DOCKER_OS} ${WORKER_ID} ${WORKSPACE}/${WORK_DIR}
                "
            """
        }  // withCredentials
    }  // withCredentials
}

// Run a single suite pulled from the queue (normal work, no special bits).
void runOneSuite(Integer WORKER_ID, Integer SEQ, String SUITE) {
    String tag = "${WORKER_ID}_${SEQ}_" + SUITE.replaceAll('[^A-Za-z0-9]', '_')
    doTests(WORKER_ID.toString(), SUITE, '', false, false, false, false, tag)
}

// Primary-worker-only: unit tests + CIFS + keyring-vault + ps-protocol + standalone tests.
// Runs once (with an empty suite list) before the primary joins the queue. Reuses the build
// node so the original "sources/" + "work/build" needed by unit tests are present.
void runSpecialWork(Integer WORKER_ID) {
    doTests(WORKER_ID.toString(), '', env.MTR_STANDALONE_TESTS ?: '',
            true,
            env.CI_FS_MTR?.trim() == 'yes',
            env.KEYRING_VAULT_MTR?.trim() == 'yes',
            env.WITH_PS_PROTOCOL?.trim() == 'yes',
            "${WORKER_ID}_special")
}

// On special-work failure, queue the enabled specials for rerun as pseudo-tokens.
@NonCPS
void recordSpecialFailures(boolean ciFs, boolean kv, boolean psProto, boolean standalone,
                           boolean unit = false) {
    def tokens = []
    if (ciFs)       tokens += ['__CI_FS__']
    if (kv)         tokens += ['__KV__']
    if (psProto)    tokens += ['__PS_PROTOCOL__']
    if (standalone) tokens += ['__STANDALONE__']
    // The unit pass needs its own token: with MTR_STANDALONE_TESTS empty nothing was recorded
    // for it, so a failed or interrupted unit run just dropped out of the retry set.
    if (unit)       tokens += ['__UNIT__']
    FAILED_SUITES = FAILED_SUITES + tokens
}

// Archive everything this worker produced. Per-suite runs set SKIP_RESULTS_TARBALL=yes, so
// the accumulated mtr_var is tarred once here.
void archiveWorkerArtifacts(Integer WORKER_ID) {
    sh """
        cd ${WORKSPACE}/${WORK_DIR}/results 2>/dev/null || exit 0
        if [ -d mtr_var ]; then
            tar --owner=0 --group=0 -czf ps80-test-mtr_logs-${WORKER_ID}.tar.gz mtr_var || :
        fi
    """
    // Note: results/*.xml is intentionally NOT archived as artifacts. JUnitResultArchiver
    // below ingests them into Jenkins' test results (the useful form), and they are still
    // synced to S3; keeping the raw XMLs as build artifacts would just be redundant.
    archiveArtifacts artifacts: "${WORK_DIR}/*.log*,${WORK_DIR}/walltimes/*.txt,${WORK_DIR}/results/ps80-test-mtr_logs-*.tar.gz", allowEmptyArchive: true
    syncDirToS3("./${WORK_DIR}/results/", "${BUILD_TAG_BINARIES}", 'mtr_var/*')
    step([$class: 'JUnitResultArchiver', testResults: "${WORK_DIR}/results/*.xml", healthScaleFactor: 1.0, keepLongStdio: false])
}

// Crash resilience: record each completed suite to S3 right after it finishes, so a build
// that dies mid-run (e.g. controller restart that can't auto-resume) can be relaunched with
// RESUME=true + BUILD_NUMBER_BINARIES=<that build> and skip the suites already done.
// Files are keyed by BUILD_NUMBER and worker id so attempts never overwrite each other and
// loadCheckpoint() can union all attempts. Best-effort: never fails the build.
void recordCheckpoint(Integer WORKER_ID, String suite) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """#!/bin/bash
            set +e
            mkdir -p ${WORK_DIR}/checkpoint
            echo '${suite}' >> ${WORK_DIR}/checkpoint/done_${env.BUILD_NUMBER}_${WORKER_ID}.txt
            aws s3 cp --no-progress --acl public-read \
                ${WORK_DIR}/checkpoint/done_${env.BUILD_NUMBER}_${WORKER_ID}.txt \
                ${S3_ROOT_DIR}/${BUILD_TAG_BINARIES}/checkpoint/done_${env.BUILD_NUMBER}_${WORKER_ID}.txt || true
            exit 0
        """
    }
}

// Return the set of suites completed by any previous attempt under BUILD_TAG_BINARIES.
List loadCheckpoint() {
    def dir = "${WORKSPACE}/resume_checkpoint"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """#!/bin/bash
            set +e
            rm -rf ${dir}; mkdir -p ${dir}
            aws s3 sync --no-progress ${S3_ROOT_DIR}/${BUILD_TAG_BINARIES}/checkpoint/ ${dir}/ || true
            cat ${dir}/done_*.txt 2>/dev/null | sort -u > ${dir}/all_done.txt
            touch ${dir}/all_done.txt
            exit 0
        """
    }
    return readFile("${dir}/all_done.txt").split('\n').collect { it.trim() }.findAll { it }
}

void checkoutSources() {
    echo 'Checkout PS sources'
    withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
        sh """
            # sudo is needed for better node recovery after compilation failure
            # if building failed on compilation stage directory will have files owned by docker user
            sudo git reset --hard
            sudo git clean -xdf || :
            sudo rm -rf sources

            # Ensure WORK_DIR is really clean (helps with re-runs / retries).
            sudo rm -rf ${WORK_DIR}
            ./local/checkout
        """
    }
}

void build(String SCRIPT) {
    timeout(time: 180, unit: 'MINUTES')  {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            sh """#!/bin/bash
                set -euo pipefail
                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                sg docker -c '
                    if docker ps -q | grep -q .; then
                        docker ps -q | xargs docker stop --time 1 || :
                    fi

                    eval USE_CCACHE=${env.USE_CCACHE} CCACHE_MAXSIZE=${env.CCACHE_MAXSIZE} KEEP_BUILD=yes ${SCRIPT} ${DOCKER_OS} ${WORKSPACE}/${WORK_DIR}
                ' 2>&1 | tee build.log

                echo "Archive build log: \$(date -u '+%s')"
                sed -i -e '
                    s^/tmp/ps/^sources/^;
                    s^/tmp/results/^sources/^;
                    s^/xz/src/build_lzma/^/third_party/xz-4.999.9beta/^;
                ' build.log
                gzip build.log
            """
        }
    }  // timeout
}

def getServerVersion() {
    echo 'Trying to get the server version'

    def versionFile = "${WORKSPACE}/VERSION-${BUILD_NUMBER}"

    withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
    withEnv(["VERSION_FILE=${versionFile}"]) {
        sh '''#!/bin/bash
            set -xe

            if [[ $USE_PR == "true" ]]; then
                # For MICRO_LABEL host import apt_get_retry(), yum_retry() from utils.inc.sh
                source ./local/utils.inc.sh

                if [ -f /usr/bin/yum ]; then
                    yum_retry makecache
                    yum_retry -y install jq
                else
                    apt_get_retry update
                    apt_get_retry install -y jq
                fi

                GIT_REPO=$(curl -s https://api.github.com/repos/percona/percona-server/pulls/$BRANCH | jq -r '.head.repo.html_url')
                BRANCH=$(curl -s https://api.github.com/repos/percona/percona-server/pulls/$BRANCH | jq -r '.head.ref')
            fi

            GIT_REPO_LINK=${GIT_REPO}
            if [[ "${GIT_REPO}" =~ (post-eol|private|eol-dev) ]]; then
                GIT_REPO_LINK=$(echo ${GIT_REPO} | sed -e "s|github|x-access-token:${JNKPercona_token}@github|g")
            fi
            RAW_VERSION_LINK=$(echo ${GIT_REPO_LINK%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")

            for FNAME in MYSQL_VERSION VERSION; do
                if curl -fsSL -o ${VERSION_FILE} ${RAW_VERSION_LINK}/${BRANCH}/${FNAME} && [[ -s ${VERSION_FILE} ]]; then
                    echo "Downloaded ${FNAME}"
                    break
                fi
                echo "Skipping ${FNAME}"
                rm -f ${VERSION_FILE}
            done
         '''
    }} // withEnv, withCredentials

    if (!fileExists(versionFile) || readFile(versionFile).trim().isEmpty()) {
        error("Neither MYSQL_VERSION nor VERSION could be downloaded")
    }

    def serverVersion = sh(
        script: """#!/bin/bash
            source "${versionFile}"
            rm -f "${versionFile}"
            echo "\${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR}.\${MYSQL_VERSION_PATCH}\${MYSQL_VERSION_EXTRA}"
        """,
        returnStdout: true
    ).trim()

    return serverVersion
}

// Build the shared suite queue. For a full run, the complete suite list is taken straight
// from the server's own mysql-test-run.pl DEFAULT_SUITES via get_default_suites_80() — no
// dependence on the hand-tuned static split. A rerun (FULL_MTR=no) loads the queue from the
// RERUN_MTR_SUITES parameter instead.
void setupSuiteQueue() {
    withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
    withEnv(["SERVER_VERSION=${SERVER_VERSION}"]) {
    sh '''#!/bin/bash
        set -xe
        rm -f ${WORKSPACE}/suites.flat
        if [[ "${FULL_MTR}" == "yes" ]]; then
            GIT_REPO_LINK=${GIT_REPO}
            if [[ "${GIT_REPO}" =~ (post-eol|private|eol-dev) ]]; then
                GIT_REPO_LINK=$(echo ${GIT_REPO} | sed -e "s|github|x-access-token:${JNKPercona_token}@github|g")
            fi
            RAW_VERSION_LINK=$(echo ${GIT_REPO_LINK%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")

            curl -fsSL ${RAW_VERSION_LINK}/${BRANCH}/mysql-test/mysql-test-run.pl -o ${WORKSPACE}/mysql-test-run.pl
            grep -q opt_only_big_test ${WORKSPACE}/mysql-test-run.pl || { echo "ERROR: Parallel MTRs require server that supports --only-big-test"; exit 1; }

            # import get_default_suites_80() from utils.inc.sh
            source ./local/utils.inc.sh
            get_default_suites_80 ${WORKSPACE}/mysql-test-run.pl | tr ',' '\\n' | awk 'NF' > ${WORKSPACE}/suites.flat
            # get_default_suites_80 only reads the DEFAULT_SUITES qw(...) block; mysql-test-run.pl
            # appends "router" at runtime for 9.5+, so it is absent from that block and would
            # never be queued. The static path adds it the same way (local/utils.inc.sh).
            if is_version_equal_or_bigger "${SERVER_VERSION}" "9.5" && [[ "${WITH_ROUTER}" == "ON" ]]; then
                echo router >> ${WORKSPACE}/suites.flat
            fi
            echo "Flat suite list (one suite per worker pull):"
            cat ${WORKSPACE}/suites.flat
        fi
    '''
    } // withEnv
    } // withCredentials

    script {
        def items = []
        if (env.FULL_MTR == 'yes') {
            items = readFile("${WORKSPACE}/suites.flat").split('\n').collect { it.trim() }.findAll { it }
        } else if (env.FULL_MTR == 'skip_mtr') {
            echo 'MTR execution skip requested!'
            env.CI_FS_MTR = 'no'
            env.WITH_PS_PROTOCOL = 'no'
            env.KEYRING_VAULT_MTR = 'no'
            env.MTR_STANDALONE_TESTS = ''
        } else {
            // FULL_MTR == 'no': manual run or aborted-suite rerun
            def src = env.RERUN_MTR_SUITES?.trim() ?: ''
            items = src.split(',').collect { it.trim() }.findAll { it }
        }
        String mtrArgs  = (env.MTR_ARGS ?: '')
        boolean onlyBig = mtrArgs.contains('--only-big-test')
        boolean bigTest = mtrArgs.contains('--big-test')
        items = expandAndOrder(items, bigTest, onlyBig)

        // Crash resilience: on a RESUME run, drop suites a previous attempt already finished.
        if (env.RESUME == 'true' && items) {
            def done = loadCheckpoint()
            if (done) {
                int before = items.size()
                items = subtractCompleted(items, done)
                echo "Resume: ${done.size()} suites completed by a previous attempt; " +
                     "skipping ${before - items.size()}, ${items.size()} remain"
            } else {
                echo "Resume requested but no checkpoint found under ${env.BUILD_TAG_BINARIES}; running full queue"
            }
        }

        loadQueue(items)
        echo "Queued ${items.size()} suite items: ${items}"
    }
}

// Re-submit a follow-up build for any suites/specials that failed or were aborted.
// Replaces the static per-worker rerun: tracks FAILED_SUITES (real suites + pseudo-tokens).
void triggerFailedSuitesRerun() {
    script {
        // Report first, gate second: with the default ALLOW_ABORTED_WORKERS_RERUN=false this
        // used to return before the echo, so a run where every worker bailed showed only its
        // failed attempts in the summary and the never-claimed suites were invisible.
        def failed = dedup(FAILED_SUITES)
        echo "Failed/aborted items (${failed.size()}): ${failed}"
        if (env.ALLOW_ABORTED_WORKERS_RERUN != 'true') {
            echo 'Automatic rerun is disabled (ALLOW_ABORTED_WORKERS_RERUN=false); not restarting them.'
            return
        }
        if (failed.isEmpty()) {
            echo 'Nothing to rerun.'
            return
        }

        def specials   = failed.findAll { it.startsWith('__') }
        def realSuites  = failed.findAll { !it.startsWith('__') }

        def ciFs    = specials.contains('__CI_FS__') ? 'yes' : 'no'
        def kv      = specials.contains('__KV__') ? 'yes' : 'no'
        def psProto = specials.contains('__PS_PROTOCOL__') ? 'yes' : 'no'
        def standalone = specials.contains('__STANDALONE__') ? (env.MTR_STANDALONE_TESTS ?: '') : ''
        def unit       = specials.contains('__UNIT__')

        echo "Restarting failed suites: ${realSuites} ; specials: ci_fs=${ciFs} kv=${kv} ps_protocol=${psProto} standalone='${standalone}' unit=${unit}"
        if (unit) {
            // Recorded and reported, but not re-run automatically: unit tests need the original
            // build tree and this rerun always reuses the parent's binaries, so the only way to
            // retest them is a fresh full build. Said out loud instead of dropped silently.
            echo 'Unit/standalone pass failed. It is NOT part of this rerun (unit tests need a rebuilt tree); start a FULL_MTR build to retest it.'
        }
        build job: "${env.PIPELINE_NAME}",
        wait: false,
        parameters: [
            string(name:'BUILD_NUMBER_BINARIES', value: BUILD_NUMBER_BINARIES_FOR_RERUN),
            string(name:'GIT_REPO', value: env.GIT_REPO),
            string(name:'BRANCH', value: env.BRANCH),
            string(name:'DOCKER_OS', value: env.DOCKER_OS),
            string(name:'ARCH', value: env.ARCH),
            string(name:'JOB_CMAKE', value: env.JOB_CMAKE),
            string(name:'COMPILER', value: env.COMPILER),
            string(name:'CMAKE_BUILD_TYPE', value: env.CMAKE_BUILD_TYPE),
            string(name:'ANALYZER_OPTS', value: env.ANALYZER_OPTS),
            string(name:'WITH_ROCKSDB', value: env.WITH_ROCKSDB),
            string(name:'WITH_ROUTER', value: env.WITH_ROUTER),
            string(name:'WITH_MYSQLX', value: env.WITH_MYSQLX),
            string(name:'WITH_JS_LANG', value: env.WITH_JS_LANG),
            string(name:'CMAKE_OPTS', value: env.CMAKE_OPTS),
            string(name:'MAKE_OPTS', value: env.MAKE_OPTS),
            string(name:'MTR_ARGS', value: env.MTR_ARGS),
            string(name:'CI_FS_MTR', value: ciFs),
            string(name:'WITH_PS_PROTOCOL', value: psProto),
            string(name:'MTR_REPEAT', value: env.MTR_REPEAT),
            string(name:'KEYRING_VAULT_MTR', value: kv),
            string(name:'KEYRING_VAULT_V1_VERSION', value: env.KEYRING_VAULT_V1_VERSION),
            string(name:'KEYRING_VAULT_V2_VERSION', value: env.KEYRING_VAULT_V2_VERSION),
            string(name:'CLOUD', value: env.CLOUD),
            string(name:'USE_CCACHE', value: env.USE_CCACHE ?: 'yes'),
            string(name:'MTR_NUM_WORKERS', value: env.MTR_NUM_WORKERS ?: '8'),
            string(name:'FULL_MTR', value:'no'),
            string(name:'RERUN_MTR_SUITES', value: realSuites.join(',')),
            string(name:'MTR_STANDALONE_TESTS', value: standalone),
            booleanParam(name: 'ALLOW_ABORTED_WORKERS_RERUN', value: false),
            string(name:'CUSTOM_BUILD_NAME', value: "${BUILD_TRIGGER_BY} ${env.CUSTOM_BUILD_NAME} (${BUILD_NUMBER} retry)")
        ]
    }
}

// functions end here

if ( (params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) ||
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON')) ) {
    PIPELINE_TIMEOUT = 48
    }

if (params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON')) {
    PIPELINE_TIMEOUT = 144
}

@NonCPS
def fetchSlackUserId(email) {
    try {
        echo "Fetching Slack User ID for email: ${email}"
        def response = slackUserIdFromEmail(email)
        return response?.toString() ?: 'Unknown User'
    } catch (Exception e) {
        echo "Error fetching Slack User ID for email '${email}': ${e.message}"
        return 'Unknown User'
    }
}

@NonCPS
def getUserEmail(userId) {
    try {
        def user = hudson.model.User.get(userId)
        return user?.getProperty(hudson.tasks.Mailer.UserProperty)?.address ?: 'No Email Found'
    } catch (Exception e) {
        echo "Failed to fetch email for User ID '${userId}': ${e.message}"
        return 'No Email Found'
    }
}

def notifySlack(status, color, customMessage) {
    script {
        try {
            def userId = params.LAUNCHER_USER_ID?.trim() ? params.LAUNCHER_USER_ID : currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)?.userId ?: 'System'
            def email = getUserEmail(userId)
            def slackUserId = fetchSlackUserId(email)

            echo "User ID: ${userId}"
            echo "Email: ${email}"
            echo "Slack User ID: ${slackUserId}"

            // Replace placeholders in the custom message
            def message = customMessage
                .replace('{status}', status)
                .replace('{userId}', userId)
                .replace('{email}', email)
                .replace('{slackUserId}', slackUserId)
                .replace('{jobName}', "<${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>")

            slackSend botUser: true,
                channel: "#${env.SLACK_CHANNEL}",
                color: color,
                message: message
        } catch (Exception e) {
            echo "Slack notification failed: ${e.message}"
        }
    }
}

// PS-11179: route via resolveArmWorker for automatic Hetzner -> AWS Graviton fallback.
def resolved = resolveArmWorker(cloud: params.CLOUD, arch: params.ARCH)
LABEL        = resolved?.label
MICRO_LABEL  = resolved?.microLabel
CLOUD_CHOSEN = resolved?.cloudChosen
resolved     = null  // drop map reference for CPS friendliness
if (!LABEL || !MICRO_LABEL || !CLOUD_CHOSEN) {
    error("resolveArmWorker returned invalid result (or null): " +
          "LABEL=${LABEL} MICRO_LABEL=${MICRO_LABEL} CLOUD_CHOSEN=${CLOUD_CHOSEN}")
}

pipeline {
    agent {
        label MICRO_LABEL
    }
    options {
        skipDefaultCheckout()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    copyArtifactPermission(env.PIPELINE_NAME)
                    echo "PIPELINE_NAME = ${env.PIPELINE_NAME}"
                    echo "NODE_NAME = ${env.NODE_NAME}"
                    echo "JENKINS_URL = ${env.JENKINS_URL}"
                    echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
                    echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
                    echo "Using instances from cloud ${CLOUD_CHOSEN} (requested CLOUD=${params.CLOUD}) with LABEL ${LABEL} for build and test stages"

                    def jenkinsUrl = env.JENKINS_URL
                    if (jenkinsUrl.startsWith('https://ps80.cd.percona.com/')) {
                        AWS_CREDENTIALS_ID = 'c8b933cd-b8ca-41d5-b639-33fe763d3f68' // ps80.cd.percona.com
                        VAULT_V1_DEV_ROOT_TOKEN = 'VAULT_V1_DEV_ROOT_TOKEN'
                        VAULT_V2_DEV_ROOT_TOKEN = 'VAULT_V2_DEV_ROOT_TOKEN'
                    } else {
                        AWS_CREDENTIALS_ID = '10ee734d-bbd1-4b4b-a611-5a2765ef9d47' // ps57.cd.percona.com
                        VAULT_V1_DEV_ROOT_TOKEN = 'VAULT_V1_DEV_TOKEN'
                        VAULT_V2_DEV_ROOT_TOKEN = 'VAULT_V2_DEV_TOKEN'
                    }
                }
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                script {
                    BUILD_TRIGGER_BY = " (${currentBuild.getBuildCauses()[0].userId})"
                    if (BUILD_TRIGGER_BY == ' (null)') {
                        BUILD_TRIGGER_BY = ' '
                    }

                    currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}/${ARCH}${BUILD_TRIGGER_BY} ${CUSTOM_BUILD_NAME}"
                }

                sh 'echo Prepare: \$(date -u "+%s")'

                script {
                    try {
                        SERVER_VERSION = getServerVersion()
                        echo "Extracted SERVER_VERSION: ${SERVER_VERSION}"
                    } catch (Exception e) {
                        error("Failed to extract server version: ${e.message}")
                    }
                }
                script {
                    try {
                        // Set BUILD_TAG_BINARIES before setupSuiteQueue so a RESUME run can read
                        // the previous attempt's checkpoint from S3 under this tag.
                        env.BUILD_TAG_BINARIES = "jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER_BINARIES}"
                        BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER_BINARIES

                        setupSuiteQueue()

                        sh 'printenv'
                    } catch (Exception e) {
                        error("Failed to setup suite queue: ${e.message}")
                    }
                }
            }
        }
        stage('Wait for instance') {
            agent { label LABEL }
            stages {
                stage('Build') {
                    when { expression { env.BUILD_NUMBER_BINARIES == '' } }
                    steps {
                        script {
                            echo "NODE_NAME = ${env.NODE_NAME}"
                            echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
                            echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
                        }
                        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                        script {
                            try {
                                checkoutSources()
                            } catch (Exception e) {
                                error("Failed to checkout sources: ${e.message}")
                            }
                        }

                        script {
                            // Set ccache size as environment variable
                            env.CCACHE_MAXSIZE = CCACHE_MAXSIZE

                            // Normalize USE_CCACHE: default to 'yes' if param is missing (PKG-1279)
                            env.USE_CCACHE = params.USE_CCACHE ?: 'yes'

                            // Set BUILD_PARAMS_TYPE based on ANALYZER_OPTS
                            if (env.ANALYZER_OPTS) {
                                if (env.ANALYZER_OPTS.contains('ASAN')) {
                                    env.BUILD_PARAMS_TYPE = 'asan'
                                } else if (env.ANALYZER_OPTS.contains('VALGRIND')) {
                                    env.BUILD_PARAMS_TYPE = 'valgrind'
                                } else if (env.ANALYZER_OPTS.contains('UBSAN')) {
                                    env.BUILD_PARAMS_TYPE = 'ubsan'
                                } else if (env.ANALYZER_OPTS.contains('MSAN')) {
                                    env.BUILD_PARAMS_TYPE = 'msan'
                                } else {
                                    env.BUILD_PARAMS_TYPE = 'standard'
                                }
                            } else {
                                env.BUILD_PARAMS_TYPE = 'standard'
                            }

                            // Extract compiler version for ccache key
                            def CC_COMPILER = env.CC ?: 'gcc'

                            env.COMPILER_VERSION = sh(returnStdout: true, script: """#!/bin/bash
                                COMPILER="${CC_COMPILER}"
                                if [[ "\$COMPILER" == *"clang"* ]]; then
                                    \$COMPILER --version | grep -o "clang version.*" | awk '{print \$3}'
                                else
                                    # For gcc, use -v to get consistent version format
                                    \$COMPILER -v 2>&1 | tail -1 | awk '{print \$3}'
                                fi || echo ''
                            """).trim()

                            // Create TOOLSET variable combining compiler and version
                            if ("${CC_COMPILER}".contains('clang')) {
                                env.TOOLSET = "clang-${env.COMPILER_VERSION}"
                            } else {
                                env.TOOLSET = "gcc-${env.COMPILER_VERSION}"
                            }

                            echo "COMPILER: ${env.COMPILER}"
                            echo "CC_COMPILER: ${CC_COMPILER}"
                            echo "COMPILER_VERSION: ${env.COMPILER_VERSION}"
                            echo "TOOLSET: ${env.TOOLSET}"
                        }

                        // Download ccache using shared library
                        ccacheDownload([
                            awsCredentialsId: CLOUD_CHOSEN == 'Hetzner' ? 'HTZ_STASH' : AWS_CREDENTIALS_ID,
                            buildParamsType: env.BUILD_PARAMS_TYPE,
                            cloud: CLOUD_CHOSEN,
                            cmakeBuildType: env.CMAKE_BUILD_TYPE,
                            dockerOs: (env.ARCH == 'aarch64') ? env.DOCKER_OS + '-aarch64' : env.DOCKER_OS,
                            forceCacheMiss: env.FORCE_CACHE_MISS == 'true',
                            serverVersion: SERVER_VERSION,
                            s3Bucket: CLOUD_CHOSEN == 'Hetzner' ? 's3://percona-jenkins-artifactory/' : S3_ROOT_DIR + '/',
                            toolset: env.TOOLSET,
                            workspace: env.WORKSPACE
                        ])

                        script {
                            try {
                                build('./docker/run-build')
                            } catch (Exception e) {
                                error("Build failed, stopping pipeline and parallel workers.")
                            }
                        }

                        // Upload ccache using shared library
                        script {
                            // Determine retention days based on build type
                            def retentionDays = (env.BUILD_PARAMS_TYPE == 'asan' || env.BUILD_PARAMS_TYPE == 'valgrind') ?
                                CACHE_RETENTION_DAYS_SANITIZER : CACHE_RETENTION_DAYS_NORMAL

                            ccacheUpload([
                                awsCredentialsId: CLOUD_CHOSEN == 'Hetzner' ? 'HTZ_STASH' : AWS_CREDENTIALS_ID,
                                buildParamsType: env.BUILD_PARAMS_TYPE,
                                cacheRetentionDays: retentionDays,
                                cloud: CLOUD_CHOSEN,
                                cmakeBuildType: env.CMAKE_BUILD_TYPE,
                                dockerOs: (env.ARCH == 'aarch64') ? env.DOCKER_OS + '-aarch64' : env.DOCKER_OS,
                                serverVersion: SERVER_VERSION,
                                s3Bucket: CLOUD_CHOSEN == 'Hetzner' ? 's3://percona-jenkins-artifactory/' : S3_ROOT_DIR + '/',
                                toolset: env.TOOLSET,
                                workspace: env.WORKSPACE
                            ])
                        }

                        script {
                            boolean archive_public_url = false
                            BIN_FILE_NAME = sh(
                                script: "ls ${WORK_DIR}/*.tar.gz | head -1",
                                returnStdout: true
                            ).trim()
                            LOG_FILE_NAME = sh(
                                script: 'ls build.log.gz | head -1',
                                returnStdout: true
                            ).trim()
                            if (BIN_FILE_NAME != '') {
                                uploadFileToS3("$BIN_FILE_NAME", "$BUILD_TAG", 'binary.tar.gz')
                                sh "rm -f $BIN_FILE_NAME"
                                sh "echo 'binary    - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/binary.tar.gz' >> public_url"
                                archive_public_url = true
                            } else {
                                error('Cannot find compiled archive')
                            }
                            if (LOG_FILE_NAME != '') {
                                uploadFileToS3("$LOG_FILE_NAME", "$BUILD_TAG", 'build.log.gz')
                                sh "echo 'build log - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/build.log.gz' >> public_url"
                                archive_public_url = true
                                archiveArtifacts 'build.log.gz'
                                sh '''
                                    gunzip build.log.gz
                                    echo "Additional artifacts:"
                                    ls | grep -xv "build.log\\|public_url" | xargs ls -l
                                '''
                                recordIssues enabledForFailure: true, tools: [gcc(pattern: 'build.log')]
                            } else {
                                echo 'Cannot find build log'
                            }

                            if (archive_public_url) {
                                archiveArtifacts 'public_url'
                            }

                            env.BUILD_TAG_BINARIES = env.BUILD_TAG
                            BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER
                        }
                    }
                }
                stage('Test') {
                    steps {
                        script {
                            int requestedWorkers = (env.MTR_NUM_WORKERS ?: '8') as int
                            int queued = queueSize()

                            // Whether the primary worker should run the unit/CIFS/KV/ps-protocol/standalone pass.
                            boolean runSpecial = (env.FULL_MTR == 'yes') ||
                                                 env.CI_FS_MTR?.trim() == 'yes' ||
                                                 env.KEYRING_VAULT_MTR?.trim() == 'yes' ||
                                                 env.WITH_PS_PROTOCOL?.trim() == 'yes' ||
                                                 (env.MTR_STANDALONE_TESTS?.trim() as boolean)

                            // Nothing to do at all (e.g. FULL_MTR=skip_mtr with empty queue).
                            if (queued == 0 && !runSpecial) {
                                echo 'No suites queued and no special work requested - skipping Test phase.'
                                return
                            }

                            // Right-size workers: never spin up more agents than there is work.
                            // Cap at the queue size, but keep at least 1 (the primary still has to
                            // run special work even when the queue is empty). This avoids allocating
                            // idle nodes for a small rerun.
                            int numWorkers = Math.min(requestedWorkers, Math.max(queued, 1))
                            echo "Dynamic MTR: ${queued} suites queued, using ${numWorkers} worker(s) (requested ${requestedWorkers})"

                            // Factory so each closure captures its worker id by value (avoids the
                            // classic mutable-loop-variable capture bug in dynamic parallel).
                            def makeWorker = { int workerId, boolean primary ->
                                return {
                                    def workerBody = {
                                        timeout(time: PIPELINE_TIMEOUT, unit: 'HOURS') {
                                            git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                                            // primary reuses the build node: keep sources/+work/build for unit tests
                                            prepareWorkspace(workerId, primary)
                                            downloadFilesForTests()
                                            dockerEcrLogin()   // log in + cache image once; per-suite runs skip it
                                            try {
                                                if (primary && runSpecial) {
                                                    long st0 = System.currentTimeMillis()
                                                    String sst = 'pass'
                                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                                        try {
                                                            runSpecialWork(workerId)
                                                        } catch (err) {
                                                            sst = 'fail'
                                                            recordSpecialFailures(
                                                                env.CI_FS_MTR?.trim() == 'yes',
                                                                env.KEYRING_VAULT_MTR?.trim() == 'yes',
                                                                env.WITH_PS_PROTOCOL?.trim() == 'yes',
                                                                env.MTR_STANDALONE_TESTS?.trim() as boolean)
                                                            throw err
                                                        } finally {
                                                            recordSuiteResult('(special: unit/KV/CIFS/ps)', workerId, 0,
                                                                (long)((System.currentTimeMillis() - st0) / 1000), sst)
                                                        }
                                                    }
                                                }
                                                int seq = 0
                                                String suite
                                                while ((suite = nextSuite()) != null) {
                                                    seq++
                                                    echo "[worker ${workerId}] picked '${suite}' (seq ${seq}); ~${queueSize()} left"
                                                    markRunning(suite, "worker-${workerId}")
                                                    long t0 = System.currentTimeMillis()
                                                    String status = 'pass'
                                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                                        try {
                                                            runOneSuite(workerId, seq, suite)
                                                            // Checkpoint only on success: a RESUME run then re-runs
                                                            // both failed and never-started suites, so failures get
                                                            // another chance even if the crash skipped the rerun trigger.
                                                            recordCheckpoint(workerId, suite)
                                                        } catch (err) {
                                                            status = 'fail'
                                                            recordFailedSuite(suite)
                                                            throw err
                                                        } finally {
                                                            recordSuiteResult(suite, workerId, seq,
                                                                (long)((System.currentTimeMillis() - t0) / 1000), status)
                                                            markDone(suite)
                                                        }
                                                    }
                                                }
                                                echo "[worker ${workerId}] queue drained, finishing."
                                            } finally {
                                                // rescue any in-flight suite interrupted by an abort
                                                sweepRunningToFailed("worker-${workerId}")
                                                archiveWorkerArtifacts(workerId)
                                                cleanWorkspace(workerId)
                                            }
                                        }
                                    }
                                    if (primary) {
                                        // reuse the 'Wait for instance' build node (has sources/ + work/build)
                                        workerBody()
                                    } else {
                                        node(LABEL) { workerBody() }
                                    }
                                }
                            }

                            def branches = [:]
                            for (int i = 1; i <= numWorkers; i++) {
                                branches["Test ${i}"] = makeWorker(i, i == 1)
                            }
                            branches.failFast = false
                            parallel branches
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', "[{jobName}]: is {status}! :rocket:\nStarted by {userId} ({email} / <@{slackUserId}>).\n${DOCKER_OS} ${ARCH} ${CMAKE_BUILD_TYPE}")
            }
        }
        failure {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', "[{jobName}]: has {status}! :face_with_peeking_eye:\nStarted by {userId} ({email} / <@{slackUserId}>).\n${DOCKER_OS} ${ARCH} ${CMAKE_BUILD_TYPE}")
            }
        }
        aborted {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', "[{jobName}]: has {status}! :axe:\nStarted by {userId} ({email} / <@{slackUserId}>).\n${DOCKER_OS} ${ARCH} ${CMAKE_BUILD_TYPE}")
            }
        }
        unstable {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', "[{jobName}]: is {status}! :warning:\nStarted by {userId} ({email} / <@{slackUserId}>).\n${DOCKER_OS} ${ARCH} ${CMAKE_BUILD_TYPE}")
            }
        }
        always {
            script {
                // Emit the suite -> worker -> duration table (and archive it for tuning / feeding
                // the heavy-suite ordering). Runs on the pipeline agent; SUITE_RESULTS lives on the
                // controller heap so it is visible here regardless of which node ran the suites.
                def summary = renderRunSummary()
                echo summary
                writeFile file: 'run-summary.txt', text: summary
                archiveArtifacts artifacts: 'run-summary.txt', allowEmptyArchive: true
            }
            triggerFailedSuitesRerun()
            echo "Finish: ${(long)(System.currentTimeMillis() / 1000)}"
        }
    }
}
