library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Cache configuration constants
// These values are hardcoded based on team requirements:
// - CCACHE_MAXSIZE: 8GB is sufficient for all build types including sanitizers
// - Retention: 60 days for normal builds, 120 days for ASAN/Valgrind builds
//
// These constants override the default retention values in the ccacheUpload shared library:
// - CACHE_RETENTION_DAYS_NORMAL: Used for standard builds
// - CACHE_RETENTION_DAYS_SANITIZER: Used for ASAN/Valgrind builds (tested once per 90-day release cycle)
//
// S3 Lifecycle Configuration Requirements:
// The S3 bucket must have lifecycle rules configured with tag-based expiration:
// - Tag "RetentionDays" = "60" → Expire after 60 days
// - Tag "RetentionDays" = "120" → Expire after 120 days
// These tags are automatically set by ccacheUpload() based on the cacheRetentionDays parameter.
// For configuration details, see PKG-769.
//
// Note for Hetzner Object Storage:
// Hetzner S3-compatible storage supports lifecycle policies but with limitations:
// - Tag-based rules are NOT supported - only prefix-based or bucket-wide rules
// - Lifecycle policies require versioning to be either disabled or enabled (not suspended)
// - Current implementation uses tag-based retention which won't work on Hetzner
// - TODO: Implement prefix-based paths for Hetzner with expiry dates (e.g., /ccache/60d/, /ccache/120d/)
//         to enable automatic object expiration via Hetzner lifecycle policies
final String CCACHE_MAXSIZE = '8G'
final int CACHE_RETENTION_DAYS_NORMAL = 60
final int CACHE_RETENTION_DAYS_SANITIZER = 120

PIPELINE_TIMEOUT = 24
MAX_S3_RETRIES = 12
S3_ROOT_DIR = 's3://ps-build-cache'
// boolean default is false, 1st item unused.
WORKER_ABORTED = new boolean[9]
BUILD_NUMBER_BINARIES_FOR_RERUN = 0
BUILD_TRIGGER_BY = ''
WORK_DIR = 'work'
SERVER_VERSION = '1.0.0'

LABEL = ''
MICRO_LABEL = ''

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

            # "sources" + "work/build" required for running unit tests are valid only for "Test 1" (WORKER #1) but not for retry/re-runs
            if [ "$WORKER_ID" = "1" ] && [ "$UNIT_TESTS" != "false" ]; then
                if [ -d sources ]; then
                    sudo cat sources/MYSQL_VERSION || :
                    if ! sudo git -C sources status >/dev/null 2>&1; then
                        echo "Warning: Git repo in ./sources is broken, removing it. It should happen only for re-runs."
                        sudo GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git -C sources -c safe.directory="$WORKSPACE/sources" log --oneline -10 || :
                        sudo rm -rf sources
                    else
                        sudo git -C sources log --oneline -10
                        sudo git -C sources reset --hard
                        sudo git -C sources clean -xdf -e . || :
                        sudo git -C sources submodule update --init || :
                    fi
                else
                    echo "Warning: Missing 'sources' for MTR worker ${WORKER_ID}. It should happen only for re-runs."
                fi
            else
                # "git clean" doesn't remove "sources/" because of ".gitignore"
                sudo git clean -xdf || :
                sudo rm -rf sources
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
    sh '''
        # "git clean" doesn't remove "sources/" because of ".gitignore"
        sudo git clean -xdf || :
        sudo rm -rf sources
    '''
}

void doTests(String WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean PS_PROTOCOL_TESTS = false) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withCredentials([
            string(credentialsId: VAULT_V1_DEV_ROOT_TOKEN, variable: VAULT_V1_DEV_ROOT_TOKEN),
            string(credentialsId: VAULT_V2_DEV_ROOT_TOKEN, variable: VAULT_V2_DEV_ROOT_TOKEN)]) {
            sh """#!/bin/bash
                echo "Starting MTR worker ${WORKER_ID}, SUITES: ${SUITES}, STANDALONE_TESTS: ${STANDALONE_TESTS}, UNIT_TESTS: ${UNIT_TESTS}, CIFS_TESTS: ${CIFS_TESTS}, KV_TESTS: ${KV_TESTS}, PS_PROTOCOL_TESTS: ${PS_PROTOCOL_TESTS}"

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

                MTR_STANDALONE_TESTS="${STANDALONE_TESTS}"
                export MTR_SUITES="${SUITES}"
                export SERVER_VERSION="${SERVER_VERSION}"

                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
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

void doTestWorkerJobWithoutGuard(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean PS_PROTOCOL_TESTS = false) {
    timeout(time: PIPELINE_TIMEOUT, unit: 'HOURS') {
        try {
            script {
                echo "NODE_NAME = ${env.NODE_NAME}"
                echo "JENKINS_SCRIPTS_BRANCH: ${JENKINS_SCRIPTS_BRANCH}"
                echo "JENKINS_SCRIPTS_REPO: ${JENKINS_SCRIPTS_REPO}"
                sh 'which git'
                sh '''
                    uname -a
                    echo "===== nproc=$(nproc --all)"
                    cat /proc/cpuinfo | grep "model name" | uniq
                    lscpu || :
                    free -m; echo
                    df -Th
                    ulimit -a
                '''
            }

            git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

            script {
                prepareWorkspace(WORKER_ID, UNIT_TESTS)
                downloadFilesForTests()
                doTests(WORKER_ID.toString(), SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS, KV_TESTS, PS_PROTOCOL_TESTS)
            }
            echo "[INFO] Worker ${WORKER_ID} finished MTR testing."
        } catch (err) {
            echo "[WARNING] Worker ${WORKER_ID} failed with error: ${err}"
            throw err // rethrow so outer catchError or pipeline failure handling still works
        } finally {
            archiveArtifacts "${WORK_DIR}/*.log*,${WORK_DIR}/walltimes/*.txt,${WORK_DIR}/results/*.xml,${WORK_DIR}/results/ps80-test-mtr_logs-*.tar.gz"

            // This is questionable. Do we need result XMLs in S3 cache while Jenkins archives them as well?
            syncDirToS3("./${WORK_DIR}/results/", "${BUILD_TAG_BINARIES}", 'mtr_var/*')
            step([$class: 'JUnitResultArchiver', testResults: "${WORK_DIR}/results/*.xml", healthScaleFactor: 1.0, keepLongStdio: false])

            // cleanup before marking success
            cleanWorkspace(WORKER_ID)

            echo "[INFO] Worker ${WORKER_ID} completed."
        }
    }
}

void doTestWorkerJobWithGuard(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean PS_PROTOCOL_TESTS = false) {
    catchError(buildResult: 'UNSTABLE') {
        script {
            WORKER_ABORTED[WORKER_ID] = true
            echo "WORKER_${WORKER_ID.toString()}_ABORTED = true"
        }
        doTestWorkerJobWithoutGuard(WORKER_ID, SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS, KV_TESTS, PS_PROTOCOL_TESTS)
        script {
            WORKER_ABORTED[WORKER_ID] = false
            echo "WORKER_${WORKER_ID.toString()}_ABORTED = false"
        }
    } // catch
}

void doTestWorkerJob(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean PS_PROTOCOL_TESTS = false) {
    script {
        if (env.ALLOW_ABORTED_WORKERS_RERUN == 'true') {
            doTestWorkerJobWithGuard(WORKER_ID, SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS, KV_TESTS, PS_PROTOCOL_TESTS)
        } else {
            doTestWorkerJobWithoutGuard(WORKER_ID, SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS, KV_TESTS, PS_PROTOCOL_TESTS)
        }
    }
}

void checkoutSources() {
    echo 'Checkout PS sources'
    withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
        sh '''
            # sudo is needed for better node recovery after compilation failure
            # if building failed on compilation stage directory will have files owned by docker user
            sudo git reset --hard
            sudo git clean -xdf || :
            sudo rm -rf sources
            ./local/checkout
        '''
    }
}

void build(String SCRIPT) {
    timeout(time: 180, unit: 'MINUTES')  {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            sh """
                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                sg docker -c "
                    if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                        docker ps -q | xargs docker stop --time 1 || :
                    fi
                    eval USE_CCACHE=${params.USE_CCACHE} CCACHE_MAXSIZE=${env.CCACHE_MAXSIZE} KEEP_BUILD=yes ${SCRIPT} ${DOCKER_OS} ${WORKSPACE}/${WORK_DIR}
                " 2>&1 | tee build.log

                echo Archive build log: \$(date -u "+%s")
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

def getServerVersionFromFile(String filePath) {
    // Call Bash to source the file and echo the version
    def serverVersion = sh(
        script: """#!/bin/bash
            if [ ! -f "${filePath}" ]; then
                echo "${filePath} not found!" >&2
                exit 1
            fi

            source "${filePath}"
            rm -f "${filePath}"

            echo "\${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR}.\${MYSQL_VERSION_PATCH}\${MYSQL_VERSION_EXTRA}"
        """,
        returnStdout: true
    ).trim()

    return serverVersion
}

def getServerVersion() {
    echo 'Trying to get the server version'

    withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
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
            REPLY=$(curl -Is ${RAW_VERSION_LINK}/${BRANCH}/MYSQL_VERSION | head -n 1 | awk '{print $2}')
            if [[ ${REPLY} != 200 ]]; then
                curl ${RAW_VERSION_LINK}/${BRANCH}/VERSION -o ${WORKSPACE}/VERSION-${BUILD_NUMBER}
            else
                curl ${RAW_VERSION_LINK}/${BRANCH}/MYSQL_VERSION -o ${WORKSPACE}/VERSION-${BUILD_NUMBER}
            fi
         '''
    } // withCredentials

    return getServerVersionFromFile("${WORKSPACE}/VERSION-${BUILD_NUMBER}")
}

void setupTestSuitesSplit() {
    withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
    withEnv(["SERVER_VERSION=${SERVER_VERSION}"]) {
    sh '''#!/bin/bash
        set -xe

        if [[ "${FULL_MTR}" == "yes" ]]; then
            # Try to get suites split from PS repo. If not present, fallback to hardcoded.
            GIT_REPO_LINK=${GIT_REPO}
            if [[ "${GIT_REPO}" =~ (post-eol|private|eol-dev) ]]; then
                GIT_REPO_LINK=$(echo ${GIT_REPO} | sed -e "s|github|x-access-token:${JNKPercona_token}@github|g")
            fi
            RAW_VERSION_LINK=$(echo ${GIT_REPO_LINK%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")

            REPLY=$(curl -Is ${RAW_VERSION_LINK}/${BRANCH}/mysql-test/suites-groups.sh | head -n 1 | awk '{print $2}')
            IGNORE_INCONSISTENCY=0
            if [[ ${REPLY} != 200 ]]; then
                # The given branch does not contain customized suites-groups.sh file. Use default configuration.
                echo "Using pipeline built-in MTR suites split"
                cp ./jenkins/suites-groups.sh ${WORKSPACE}/suites-groups.sh
            else
                echo "Using custom MTR suites split"
                curl ${RAW_VERSION_LINK}/${BRANCH}/mysql-test/suites-groups.sh -o ${WORKSPACE}/suites-groups.sh
            fi
            curl ${RAW_VERSION_LINK}/${BRANCH}/mysql-test/mysql-test-run.pl -o ${WORKSPACE}/mysql-test-run.pl
            grep -q opt_only_big_test ${WORKSPACE}/mysql-test-run.pl || { echo "ERROR: Parallel MTRs require server that supports --only-big-test"; exit 1; }

            # import check_suites() from utils.inc.sh; check_suites() may be overwritten in suites-groups.sh
            source ./local/utils.inc.sh
            # Check if split contain all suites
            chmod +x ${WORKSPACE}/suites-groups.sh
            set +e
            echo "Check if suites list is consistent with the one specified in mysql-test-run.pl"
            source ${WORKSPACE}/suites-groups.sh
            echo "IGNORE_INCONSISTENCY=${IGNORE_INCONSISTENCY}"
            if [[ "${ANALYZER_OPTS}" == *"-DWITH_VALGRIND=ON"* ]]; then
                set_suites Valgrind ${SERVER_VERSION}
            else
                set_suites ${CMAKE_BUILD_TYPE} ${SERVER_VERSION}
            fi
            set +x
            check_suites ${WORKSPACE}/mysql-test-run.pl ${SERVER_VERSION}
            CHECK_RESULT=$?
            set -xe
            echo "CHECK_RESULT: ${CHECK_RESULT}"
            # Fail only if this is built-in split.
            if [[ ${IGNORE_INCONSISTENCY} -eq 0 ]] && [[ ${CHECK_RESULT} -ne 0 ]]; then
                echo "Default MTR split is inconsistent. Exiting."
                exit 1
            fi

            echo ${WORKER_1_MTR_SUITES} > ${WORKSPACE}/worker_1.suites
            echo ${WORKER_2_MTR_SUITES} > ${WORKSPACE}/worker_2.suites
            echo ${WORKER_3_MTR_SUITES} > ${WORKSPACE}/worker_3.suites
            echo ${WORKER_4_MTR_SUITES} > ${WORKSPACE}/worker_4.suites
            echo ${WORKER_5_MTR_SUITES} > ${WORKSPACE}/worker_5.suites
            echo ${WORKER_6_MTR_SUITES} > ${WORKSPACE}/worker_6.suites
            echo ${WORKER_7_MTR_SUITES} > ${WORKSPACE}/worker_7.suites
            echo ${WORKER_8_MTR_SUITES} > ${WORKSPACE}/worker_8.suites
        fi
    '''
    } // withEnv
    } // withCredentials

    script {
        if (env.FULL_MTR == 'yes') {
            env.WORKER_1_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_1.suites").trim()
            env.WORKER_2_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_2.suites").trim()
            env.WORKER_3_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_3.suites").trim()
            env.WORKER_4_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_4.suites").trim()
            env.WORKER_5_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_5.suites").trim()
            env.WORKER_6_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_6.suites").trim()
            env.WORKER_7_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_7.suites").trim()
            env.WORKER_8_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_8.suites").trim()
        } else if (env.FULL_MTR == 'skip_mtr') {
            // It is possible that values are fetched from
            // suites-groups.sh file. Clean them.
            echo 'MTR execution skip requested!'
            env.WORKER_1_MTR_SUITES = ''
            env.WORKER_2_MTR_SUITES = ''
            env.WORKER_3_MTR_SUITES = ''
            env.WORKER_4_MTR_SUITES = ''
            env.WORKER_5_MTR_SUITES = ''
            env.WORKER_6_MTR_SUITES = ''
            env.WORKER_7_MTR_SUITES = ''
            env.WORKER_8_MTR_SUITES = ''
            env.CI_FS_MTR = 'no'
            env.WITH_PS_PROTOCOL = 'no'
            env.KEYRING_VAULT_MTR = 'no'
        }

        echo "WORKER_1_MTR_SUITES: ${env.WORKER_1_MTR_SUITES}"
        echo "WORKER_2_MTR_SUITES: ${env.WORKER_2_MTR_SUITES}"
        echo "WORKER_3_MTR_SUITES: ${env.WORKER_3_MTR_SUITES}"
        echo "WORKER_4_MTR_SUITES: ${env.WORKER_4_MTR_SUITES}"
        echo "WORKER_5_MTR_SUITES: ${env.WORKER_5_MTR_SUITES}"
        echo "WORKER_6_MTR_SUITES: ${env.WORKER_6_MTR_SUITES}"
        echo "WORKER_7_MTR_SUITES: ${env.WORKER_7_MTR_SUITES}"
        echo "WORKER_8_MTR_SUITES: ${env.WORKER_8_MTR_SUITES}"
    }
}

void triggerAbortedTestWorkersRerun() {
    script {
        if (env.ALLOW_ABORTED_WORKERS_RERUN == 'true') {
            echo "allow aborted reruns ${env.ALLOW_ABORTED_WORKERS_RERUN}"
            echo "WORKER_1_ABORTED: ${WORKER_ABORTED[1]}"
            echo "WORKER_2_ABORTED: ${WORKER_ABORTED[2]}"
            echo "WORKER_3_ABORTED: ${WORKER_ABORTED[3]}"
            echo "WORKER_4_ABORTED: ${WORKER_ABORTED[4]}"
            echo "WORKER_5_ABORTED: ${WORKER_ABORTED[5]}"
            echo "WORKER_6_ABORTED: ${WORKER_ABORTED[6]}"
            echo "WORKER_7_ABORTED: ${WORKER_ABORTED[7]}"
            echo "WORKER_8_ABORTED: ${WORKER_ABORTED[8]}"
            def rerunNeeded = false
            def WORKER_1_RERUN_SUITES = ''
            def WORKER_2_RERUN_SUITES = ''
            def WORKER_3_RERUN_SUITES = ''
            def WORKER_4_RERUN_SUITES = ''
            def WORKER_5_RERUN_SUITES = ''
            def WORKER_6_RERUN_SUITES = ''
            def WORKER_7_RERUN_SUITES = ''
            def WORKER_8_RERUN_SUITES = ''

            if (WORKER_ABORTED[1]) {
                echo 'rerun worker 1'
                WORKER_1_RERUN_SUITES = env.WORKER_1_MTR_SUITES
                rerunNeeded = true
            } else {
                // Prevent CI_FS re-trigger
                env.CI_FS_MTR = 'no'
            }
            if (WORKER_ABORTED[2]) {
                echo 'rerun worker 2'
                WORKER_2_RERUN_SUITES = env.WORKER_2_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[3]) {
                echo 'rerun worker 3'
                WORKER_3_RERUN_SUITES = env.WORKER_3_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[4]) {
                echo 'rerun worker 4'
                WORKER_4_RERUN_SUITES = env.WORKER_4_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[5]) {
                echo 'rerun worker 5'
                WORKER_5_RERUN_SUITES = env.WORKER_5_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[6]) {
                echo 'rerun worker 6'
                WORKER_6_RERUN_SUITES = env.WORKER_6_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[7]) {
                echo 'rerun worker 7'
                WORKER_7_RERUN_SUITES = env.WORKER_7_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[8]) {
                echo 'rerun worker 8'
                WORKER_8_RERUN_SUITES = env.WORKER_8_MTR_SUITES
                rerunNeeded = true
            }

            echo "rerun needed: $rerunNeeded"
            if (rerunNeeded) {
                echo 'restarting aborted workers'
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
                    string(name:'CI_FS_MTR', value: env.CI_FS_MTR),
                    string(name:'WITH_PS_PROTOCOL', value: env.WITH_PS_PROTOCOL),
                    string(name:'GALERA_PARALLEL_RUN', value: env.GALERA_PARALLEL_RUN),
                            string(name:'MTR_REPEAT', value: env.MTR_REPEAT),
                            string(name:'KEYRING_VAULT_MTR', value: env.KEYRING_VAULT_MTR),
                            string(name:'KEYRING_VAULT_V1_VERSION', value: env.KEYRING_VAULT_V1_VERSION),
                            string(name:'KEYRING_VAULT_V2_VERSION', value: env.KEYRING_VAULT_V2_VERSION),
                            string(name:'CLOUD', value: env.CLOUD),
                    string(name:'FULL_MTR', value:'no'),
                    string(name:'WORKER_1_MTR_SUITES', value: WORKER_1_RERUN_SUITES),
                    string(name:'WORKER_2_MTR_SUITES', value: WORKER_2_RERUN_SUITES),
                    string(name:'WORKER_3_MTR_SUITES', value: WORKER_3_RERUN_SUITES),
                    string(name:'WORKER_4_MTR_SUITES', value: WORKER_4_RERUN_SUITES),
                    string(name:'WORKER_5_MTR_SUITES', value: WORKER_5_RERUN_SUITES),
                    string(name:'WORKER_6_MTR_SUITES', value: WORKER_6_RERUN_SUITES),
                    string(name:'WORKER_7_MTR_SUITES', value: WORKER_7_RERUN_SUITES),
                    string(name:'WORKER_8_MTR_SUITES', value: WORKER_8_RERUN_SUITES),
                    string(name:'MTR_STANDALONE_TESTS', value: MTR_STANDALONE_TESTS),
                    string(name:'MTR_STANDALONE_TESTS_PARALLEL', value: MTR_STANDALONE_TESTS_PARALLEL),
                    booleanParam(name: 'ALLOW_ABORTED_WORKERS_RERUN', value: false),
                    string(name:'CUSTOM_BUILD_NAME', value: "${BUILD_TRIGGER_BY} ${env.CUSTOM_BUILD_NAME} (${BUILD_NUMBER} retry)")
                ]
            }
        }  // env.ALLOW_ABORTED_WORKERS_RERUN
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

if (params.CLOUD == 'Hetzner') {
    LABEL = (params.ARCH == 'aarch64') ? 'docker-aarch64' : 'docker-x64'
    MICRO_LABEL = 'launcher-x64'
} else if (params.CLOUD == 'epyc9654') {
    LABEL = 'as-1015cs-tnr'
    MICRO_LABEL = 'launcher-x64'
} else {
    // by default fallback to AWS
    LABEL = (params.ARCH == 'aarch64') ? 'docker-32gb-aarch64' : 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
}

pipeline {
    agent {
        label MICRO_LABEL
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
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
                    echo "Using instances from cloud ${CLOUD} with LABEL ${LABEL} for build and test stages"

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
                    SERVER_VERSION = getServerVersion()
                    echo "Extracted SERVER_VERSION: ${SERVER_VERSION}"
                    setupTestSuitesSplit()

                    env.BUILD_TAG_BINARIES = "jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER_BINARIES}"
                    BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER_BINARIES
                    sh 'printenv'
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

                        checkoutSources()

                        script {
                            // Set ccache size as environment variable
                            env.CCACHE_MAXSIZE = CCACHE_MAXSIZE
                            
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
                            awsCredentialsId: AWS_CREDENTIALS_ID,
                            buildParamsType: env.BUILD_PARAMS_TYPE,
                            cloud: params.CLOUD,
                            cmakeBuildType: env.CMAKE_BUILD_TYPE,
                            dockerOs: (env.ARCH == 'aarch64') ? env.DOCKER_OS + '-aarch64' : env.DOCKER_OS,
                            forceCacheMiss: env.FORCE_CACHE_MISS == 'true',
                            serverVersion: SERVER_VERSION,
                            s3Bucket: S3_ROOT_DIR + '/',
                            toolset: env.TOOLSET,
                            workspace: env.WORKSPACE
                        ])

                        build('./docker/run-build')

                        // Upload ccache using shared library
                        script {
                            // Determine retention days based on build type
                            def retentionDays = (env.BUILD_PARAMS_TYPE == 'asan' || env.BUILD_PARAMS_TYPE == 'valgrind') ?
                                CACHE_RETENTION_DAYS_SANITIZER : CACHE_RETENTION_DAYS_NORMAL

                            ccacheUpload([
                                awsCredentialsId: AWS_CREDENTIALS_ID,
                                buildParamsType: env.BUILD_PARAMS_TYPE,
                                cacheRetentionDays: retentionDays,
                                cloud: params.CLOUD,
                                cmakeBuildType: env.CMAKE_BUILD_TYPE,
                                dockerOs: (env.ARCH == 'aarch64') ? env.DOCKER_OS + '-aarch64' : env.DOCKER_OS,
                                serverVersion: SERVER_VERSION,
                                s3Bucket: S3_ROOT_DIR + '/',
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
                                echo 'Cannot find compiled archive log'
                                currentBuild.result = 'FAILURE'
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
                    parallel {
                        stage('Test 1') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_1_MTR_SUITES?.trim() || env.MTR_STANDALONE_TESTS?.trim() || env.CI_FS_MTR?.trim() == 'yes' || env.KEYRING_VAULT_MTR?.trim() == 'yes' || env.WITH_PS_PROTOCOL?.trim() == 'yes') }
                            }
                            // "Test 1" reuses the same instance as "Build" and inherits "sources" + "work/build" which are required for running unit tests
                            // agent { label LABEL }
                            steps {
                                doTestWorkerJob(1, "${WORKER_1_MTR_SUITES}", "${MTR_STANDALONE_TESTS}", true, env.CI_FS_MTR?.trim() == 'yes', env.KEYRING_VAULT_MTR?.trim() == 'yes', env.WITH_PS_PROTOCOL?.trim() == 'yes')
                            }
                        }
                        stage('Test 2') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_2_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(2, "${WORKER_2_MTR_SUITES}")
                            }
                        }
                        stage('Test 3') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_3_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(3, "${WORKER_3_MTR_SUITES}")
                            }
                        }
                        stage('Test 4') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_4_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(4, "${WORKER_4_MTR_SUITES}")
                            }
                        }
                        stage('Test 5') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_5_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(5, "${WORKER_5_MTR_SUITES}")
                            }
                        }
                        stage('Test 6') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_6_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(6, "${WORKER_6_MTR_SUITES}")
                            }
                        }
                        stage('Test 7') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_7_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(7, "${WORKER_7_MTR_SUITES}")
                            }
                        }
                        stage('Test 8') {
                            when {
                                beforeAgent true
                                expression { (env.WORKER_8_MTR_SUITES?.trim()) }
                            }
                            agent { label LABEL }
                            steps {
                                doTestWorkerJob(8, "${WORKER_8_MTR_SUITES}")
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', '[{jobName}]: is {status}! :rocket: Started by {userId} ({email} / <@{slackUserId}>).')
            }
        }
        failure {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', '[{jobName}]: has {status}! :face_with_peeking_eye: Started by {userId} ({email} / <@{slackUserId}>).')
            }
        }
        aborted {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', '[{jobName}]: has {status}! :axe: Started by {userId} ({email} / <@{slackUserId}>).')
            }
        }
        unstable {
            script {
                notifySlack(currentBuild.currentResult, '#36a64f', '[{jobName}]: is {status}! :warning: Started by {userId} ({email} / <@{slackUserId}>).')
            }
        }
        always {
            triggerAbortedTestWorkersRerun()
            sh 'echo Finish: \$(date -u "+%s")'
        }
    }
}
