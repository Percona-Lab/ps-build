PIPELINE_TIMEOUT = 24
JENKINS_SCRIPTS_BRANCH = '8.0'
JENKINS_SCRIPTS_REPO = 'https://github.com/Percona-Lab/ps-build'
AWS_CREDENTIALS_ID = 'c8b933cd-b8ca-41d5-b639-33fe763d3f68'
MAX_S3_RETRIES = 12
S3_ROOT_DIR = 's3://ps-build-cache'
// boolean default is false, 1st item unused.
WORKER_ABORTED = new boolean[9]
BUILD_NUMBER_BINARIES_FOR_RERUN = 0
BUILD_TRIGGER_BY = ''
PXB24_PACKAGE_TO_DOWNLOAD = ''
PXB80_PACKAGE_TO_DOWNLOAD = ''

def ZEN_FS_MTR_SUPPORTED = false
def LABEL = 'docker-32gb'


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
    downloadFileFromS3("${BUILD_TAG_BINARIES}", "binary.tar.gz", "./sources/results/binary.tar.gz")
}

void prepareWorkspace() {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            sudo git reset --hard
            sudo git clean -xdf
            rm -rf sources/results
            sudo git -C sources reset --hard || :
            sudo git -C sources clean -xdf   || :

            if [ -f /usr/bin/yum ]; then
                sudo yum -y install jq gflags-devel
            else
                sudo apt-get install -y jq libgflags-dev libjemalloc-dev
            fi
        """
    }
}

void doTests(String WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean ZENFS_TESTS = false) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withCredentials([
            string(credentialsId: 'VAULT_V1_DEV_ROOT_TOKEN', variable: 'VAULT_V1_DEV_ROOT_TOKEN'),
            string(credentialsId: 'VAULT_V2_DEV_ROOT_TOKEN', variable: 'VAULT_V2_DEV_ROOT_TOKEN')]) {
            sh """#!/bin/bash
                echo "Starting MTR worker ${WORKER_ID}, SUITES: ${SUITES}, STANDALONE_TESTS: ${STANDALONE_TESTS}, UNIT_TESTS: ${UNIT_TESTS}, CIFS_TESTS: ${CIFS_TESTS}, KV_TESTS: ${KV_TESTS}, ZENFS_TESTS: ${ZENFS_TESTS}"

                if [[ "${CIFS_TESTS}" == "true" ]]; then
                    echo "Preparing filesystem for CIFS tests"

                    if [ -f /usr/bin/yum ]; then
                        sudo yum -y dosfstools
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
                if [[ "${KV_TESTS}" == "false" ]]; then
                    echo "Disabling Keyring Vault mtr"
                    KEYRING_VAULT_MTR=no
                else
                    echo "Enabling Keyring Vault mtr"
                    KEYRING_VAULT_MTR=yes
                fi
                if [[ "${ZENFS_TESTS}" == "false" ]]; then
                    echo "Disabling ZenFS mtr"
                    ZEN_FS_MTR=no
                else
                    echo "Enabling ZenFS mtr"
                    ZEN_FS_MTR=yes
                fi

                MTR_STANDALONE_TESTS="${STANDALONE_TESTS}"
                export MTR_SUITES="${SUITES}"

                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                sg docker -c "
                    if [ \$(docker ps -a -q | wc -l) -ne 0 ]; then
                        docker ps -q | xargs docker stop --time 1 || :
                        docker rm --force consul vault-prod-v{1..2} vault-dev-v{1..2} || :
                    fi
                    ./docker/run-test-parallel-mtr ${DOCKER_OS} ${WORKER_ID}
                "
            """
        }  // withCredentials
    }  // withCredentials
}

void doTestWorkerJob(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean ZENFS_TESTS = false) {
    timeout(time: PIPELINE_TIMEOUT, unit: 'HOURS')  {
        script {
            echo "JENKINS_SCRIPTS_BRANCH: ${JENKINS_SCRIPTS_BRANCH}"
            echo "JENKINS_SCRIPTS_REPO: ${JENKINS_SCRIPTS_REPO}"
            sh "which git"
        }
        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
        script {
            prepareWorkspace()
            downloadFilesForTests()
            doTests(WORKER_ID.toString(), SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS, KV_TESTS, ZENFS_TESTS)
        }

        // This is questionable. Do we need resutl XMLs in S3 cache while Jenkins archives them as well?
        syncDirToS3("./sources/results/", "${BUILD_TAG_BINARIES}", "binary.tar.gz")
        step([$class: 'JUnitResultArchiver', testResults: 'sources/results/*.xml', healthScaleFactor: 1.0])
        archiveArtifacts 'sources/results/*.xml,sources/results/ps80-test-mtr_logs-*.tar.gz'
    }
}

void doTestWorkerJobWithGuard(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false, boolean KV_TESTS = false, boolean ZENFS_TESTS = false) {
    catchError(buildResult: 'UNSTABLE') {
        script {
            WORKER_ABORTED[WORKER_ID] = true
            echo "WORKER_${WORKER_ID.toString()}_ABORTED = true"
        }
        doTestWorkerJob(WORKER_ID, SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS, KV_TESTS, ZENFS_TESTS)
        script {
            WORKER_ABORTED[WORKER_ID] = false
            echo "WORKER_${WORKER_ID.toString()}_ABORTED = false"
        }
    } // catch
}

void checkoutSources() {
    echo "Checkout PS sources"
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf sources
        ./local/checkout
    """
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
                    eval ${SCRIPT} ${DOCKER_OS}
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

void setupTestSuitesSplit() {
    sh """#!/bin/bash
        if [[ "${FULL_MTR}" == "yes" ]]; then
            # Try to get suites split from PS repo. If not present, fallback to hardcoded.
            RAW_VERSION_LINK=\$(echo \${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
            REPLY=\$(curl -Is \${RAW_VERSION_LINK}/${BRANCH}/mysql-test/suites-groups.sh | head -n 1 | awk '{print \$2}')
            CUSTOM_SPLIT=0
            if [[ \${REPLY} != 200 ]]; then
                # The given branch does not contain customized suites-groups.sh file. Use default configuration.
                echo "Using pipeline built-in MTR suites split"
                cp ./jenkins/suites-groups.sh ${WORKSPACE}/suites-groups.sh
            else
                echo "Using custom MTR suites split"
                wget \${RAW_VERSION_LINK}/${BRANCH}/mysql-test/suites-groups.sh -O ${WORKSPACE}/suites-groups.sh
                CUSTOM_SPLIT=1
            fi
            # Check if split contain all suites
            wget \${RAW_VERSION_LINK}/${BRANCH}/mysql-test/mysql-test-run.pl -O ${WORKSPACE}/mysql-test-run.pl
            chmod +x ${WORKSPACE}/suites-groups.sh
            set +e
            ${WORKSPACE}/suites-groups.sh check ${WORKSPACE}/mysql-test-run.pl
            CHECK_RESULT=\$?
            set -e
            echo "CHECK_RESULT: \${CHECK_RESULT}"
            # Fail only if this is built-in split.
            if [[ \${CUSTOM_SPLIT} -eq 0 ]] && [[ \${CHECK_RESULT} -ne 0 ]]; then
                echo "Default MTR split is inconsistent. Exiting."
                exit 1
            fi

            # Source suites split definition
            source ${WORKSPACE}/suites-groups.sh

            echo \${WORKER_1_MTR_SUITES} > ${WORKSPACE}/worker_1.suites
            echo \${WORKER_2_MTR_SUITES} > ${WORKSPACE}/worker_2.suites
            echo \${WORKER_3_MTR_SUITES} > ${WORKSPACE}/worker_3.suites
            echo \${WORKER_4_MTR_SUITES} > ${WORKSPACE}/worker_4.suites
            echo \${WORKER_5_MTR_SUITES} > ${WORKSPACE}/worker_5.suites
            echo \${WORKER_6_MTR_SUITES} > ${WORKSPACE}/worker_6.suites
            echo \${WORKER_7_MTR_SUITES} > ${WORKSPACE}/worker_7.suites
            echo \${WORKER_8_MTR_SUITES} > ${WORKSPACE}/worker_8.suites
        fi
    """
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
            echo "MTR execution skip requested!"
            env.WORKER_1_MTR_SUITES = ""
            env.WORKER_2_MTR_SUITES = ""
            env.WORKER_3_MTR_SUITES = ""
            env.WORKER_4_MTR_SUITES = ""
            env.WORKER_5_MTR_SUITES = ""
            env.WORKER_6_MTR_SUITES = ""
            env.WORKER_7_MTR_SUITES = ""
            env.WORKER_8_MTR_SUITES = ""
            env.CI_FS_MTR = 'no'
            env.ZEN_FS_MTR = 'no'
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
            def WORKER_1_RERUN_SUITES = ""
            def WORKER_2_RERUN_SUITES = ""
            def WORKER_3_RERUN_SUITES = ""
            def WORKER_4_RERUN_SUITES = ""
            def WORKER_5_RERUN_SUITES = ""
            def WORKER_6_RERUN_SUITES = ""
            def WORKER_7_RERUN_SUITES = ""
            def WORKER_8_RERUN_SUITES = ""

            if (WORKER_ABORTED[1]) {
                echo "rerun worker 1"
                WORKER_1_RERUN_SUITES = env.WORKER_1_MTR_SUITES
                rerunNeeded = true
            } else {
                // Prevent CI_FS re-trigger
                env.CI_FS_MTR = 'no'
            }
            if (WORKER_ABORTED[2]) {
                echo "rerun worker 2"
                WORKER_2_RERUN_SUITES = env.WORKER_2_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[3]) {
                echo "rerun worker 3"
                WORKER_3_RERUN_SUITES = env.WORKER_3_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[4]) {
                echo "rerun worker 4"
                WORKER_4_RERUN_SUITES = env.WORKER_4_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[5]) {
                echo "rerun worker 5"
                WORKER_5_RERUN_SUITES = env.WORKER_5_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[6]) {
                echo "rerun worker 6"
                WORKER_6_RERUN_SUITES = env.WORKER_6_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[7]) {
                echo "rerun worker 7"
                WORKER_7_RERUN_SUITES = env.WORKER_7_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[8]) {
                echo "rerun worker 8"
                WORKER_8_RERUN_SUITES = env.WORKER_8_MTR_SUITES
                rerunNeeded = true
            }

            echo "rerun needed: $rerunNeeded"
            if (rerunNeeded) {
                echo "restarting aborted workers"
                build job: 'percona-server-8.0-pipeline-parallel-mtr',
                wait: false,
                parameters: [
                    string(name:'BUILD_NUMBER_BINARIES', value: BUILD_NUMBER_BINARIES_FOR_RERUN),
                    string(name:'GIT_REPO', value: env.GIT_REPO),
                    string(name:'BRANCH', value: env.BRANCH),
                    string(name:'DOCKER_OS', value: env.DOCKER_OS),
                    string(name:'JOB_CMAKE', value: env.JOB_CMAKE),
                    string(name:'CMAKE_BUILD_TYPE', value: env.CMAKE_BUILD_TYPE),
                    string(name:'ANALYZER_OPTS', value: env.ANALYZER_OPTS),
                            string(name:'WITH_ROCKSDB', value: env.WITH_ROCKSDB),
                            string(name:'WITH_ROUTER', value: env.WITH_ROUTER),
                            string(name:'WITH_MYSQLX', value: env.WITH_MYSQLX),
                            string(name:'WITH_KEYRING_VAULT', value: env.WITH_KEYRING_VAULT),
                    string(name:'CMAKE_OPTS', value: env.CMAKE_OPTS),
                    string(name:'MAKE_OPTS', value: env.MAKE_OPTS),
                            string(name:'ZEN_FS_MTR', value: env.ZEN_FS_MTR),
                    string(name:'MTR_ARGS', value: env.MTR_ARGS),
                    string(name:'CI_FS_MTR', value: env.CI_FS_MTR),
                    string(name:'GALERA_PARALLEL_RUN', value: env.GALERA_PARALLEL_RUN),
                            string(name:'MTR_REPEAT', value: env.MTR_REPEAT),
                            string(name:'KEYRING_VAULT_MTR', value: env.KEYRING_VAULT_MTR),
                            string(name:'KEYRING_VAULT_V1_VERSION', value: env.KEYRING_VAULT_V1_VERSION),
                            string(name:'KEYRING_VAULT_V2_VERSION', value: env.KEYRING_VAULT_V2_VERSION),
                            string(name:'LABEL', value: env.LABEL),
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

void validatePsBranch() {
    echo "Validating PS branch version"
    sh """#!/bin/bash
        MY_BRANCH_BASE_MAJOR=8
        MY_BRANCH_BASE_MINOR=0

        if [ -f /usr/bin/apt ]; then
            sudo apt-get update
        fi

        if [[ ${USE_PR} == "true" ]]; then
            if [ -f /usr/bin/yum ]; then
                sudo yum -y install jq
            else
                sudo apt-get install -y jq
            fi

            GIT_REPO=\$(curl https://api.github.com/repos/percona/percona-server/pulls/${BRANCH} | jq -r '.head.repo.html_url')
            BRANCH=\$(curl https://api.github.com/repos/percona/percona-server/pulls/${BRANCH} | jq -r '.head.ref')
        fi

        RAW_VERSION_LINK=\$(echo \${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
        REPLY=\$(curl -Is \${RAW_VERSION_LINK}/\${BRANCH}/MYSQL_VERSION | head -n 1 | awk '{print \$2}')
        if [[ \${REPLY} != 200 ]]; then
            wget \${RAW_VERSION_LINK}/\${BRANCH}/VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
        else
            wget \${RAW_VERSION_LINK}/\${BRANCH}/MYSQL_VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
        fi
        source ${WORKSPACE}/VERSION-${BUILD_NUMBER}
        if [[ \${MYSQL_VERSION_MAJOR} -lt \${MY_BRANCH_BASE_MAJOR} ]] ; then
            echo "Are you trying to build wrong branch?"
            echo "You are trying to build \${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR} instead of \${MY_BRANCH_BASE_MAJOR}.\${MY_BRANCH_BASE_MINOR}!"
            rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
            exit 1
        fi
        rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
    """
}

// functions end here

if ( (params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) ||
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON')) )
{
    PIPELINE_TIMEOUT = 48
}

if (params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON'))
{
    PIPELINE_TIMEOUT = 144
}

if ( ((params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) &&
     (params.ANALYZER_OPTS.contains('-DWITH_ASAN_SCOPE=ON')) &&
     (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON'))) ||
     ((params.MTR_ARGS.contains('--big-test')) || (params.MTR_ARGS.contains('--only-big-test'))) )
{
    LABEL = 'docker-32gb'
    PIPELINE_TIMEOUT = 20
}

if ( (params.ZEN_FS_MTR == 'yes') &&
     ((params.DOCKER_OS == 'oraclelinux:9') ||
      (params.DOCKER_OS == 'ubuntu:jammy') ||
      (params.DOCKER_OS == 'debian:bullseye')
     ) )
{
    LABEL = 'docker-32gb-bullseye'
    PIPELINE_TIMEOUT = 22
    ZEN_FS_MTR_SUPPORTED = true
}
else
{
    // Do not allow ZEN_FS test. It is not possible to execute them on requested platform anyway.
    script {
        echo "ZEN_FS tests disabled. Unsupported OS: ${params.DOCKER_OS}"
    }
}

pipeline {
    parameters {
        string(
            defaultValue: '',
            description: 'Reuse binaries built in the specified build. Useful for quick MTR test rerun without rebuild.',
            name: 'BUILD_NUMBER_BINARIES',
            trim: true)
        string(
            defaultValue: 'https://github.com/percona/percona-server',
            description: 'URL to percona-server repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-server repository',
            name: 'BRANCH',
            trim: true)
        string(
            defaultValue: '',
            description: 'Custom string that will be appended to the build name visible in Jenkins',
            name: 'CUSTOM_BUILD_NAME',
            trim: true)
        booleanParam(
            defaultValue: false,
            description: 'Check only if you pass PR number to BRANCH field',
            name: 'USE_PR')
        choice(
            choices: 'centos:7\ncentos:8\noraclelinux:9\nubuntu:bionic\nubuntu:focal\nubuntu:jammy\ndebian:buster\ndebian:bullseye\ndebian:bookworm',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: 'RelWithDebInfo\nDebug',
            description: 'Type of build to produce',
            name: 'CMAKE_BUILD_TYPE')
        choice(
            choices: '/usr/bin/cmake',
            description: 'path to cmake binary',
            name: 'JOB_CMAKE')
        choice(
            choices: '\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON\n-DWITH_ASAN=ON\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON -DWITH_UBSAN=ON\n-DWITH_ASAN=ON -DWITH_UBSAN=ON\n-DWITH_UBSAN=ON\n-DWITH_MSAN=ON\n-DWITH_VALGRIND=ON',
            description: 'Enable code checking',
            name: 'ANALYZER_OPTS')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile RocksDB engine',
            name: 'WITH_ROCKSDB')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build MySQL Router',
            name: 'WITH_ROUTER')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build with support for X Plugin',
            name: 'WITH_MYSQLX')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build with support for keyring_vault Plugin',
            name: 'WITH_KEYRING_VAULT')
        string(
            defaultValue: '',
            description: 'cmake options',
            name: 'CMAKE_OPTS')
        string(
            defaultValue: '',
            description: 'make options, like VERBOSE=1',
            name: 'MAKE_OPTS')
        choice(
            choices: 'no\nyes',
            description: 'Run ZenFS MTR tests',
            name: 'ZEN_FS_MTR')
        choice(
            choices: 'yes\nno',
            description: 'Run case-insensetive MTR tests',
            name: 'CI_FS_MTR')
        string(
            defaultValue: '--unit-tests-report --mem --big-test',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
        choice(
            choices: 'no\nyes',
            description: 'Run mtr --suite=keyring_vault',
            name: 'KEYRING_VAULT_MTR')
        string(
            defaultValue: '0.9.6',
            description: 'Specifies version of Hashicorp Vault for V1 tests',
            name: 'KEYRING_VAULT_V1_VERSION'
        )
        string(
            defaultValue: '1.9.0',
            description: 'Specifies version of Hashicorp Vault for V2 tests',
            name: 'KEYRING_VAULT_V2_VERSION'
        )
        choice(
            choices: 'docker-32gb\ndocker',
            description: 'Run build on specified instance type',
            name: 'LABEL')
        choice(
            choices: 'yes\nno\nskip_mtr',
            description: 'yes - full MTR\nno - run mtr suites based on variables WORKER_N_MTR_SUITES\nskip_mtr - skip testing phase. Only build.',
            name: 'FULL_MTR')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 1 when FULL_MTR is no. Unit tests, if requested, can be ran here only!',
            name: 'WORKER_1_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 2 when FULL_MTR is no',
            name: 'WORKER_2_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 3 when FULL_MTR is no',
            name: 'WORKER_3_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 4 when FULL_MTR is no',
            name: 'WORKER_4_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 5 when FULL_MTR is no',
            name: 'WORKER_5_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 6 when FULL_MTR is no',
            name: 'WORKER_6_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 7 when FULL_MTR is no',
            name: 'WORKER_7_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 8 when FULL_MTR is no',
            name: 'WORKER_8_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Space-separated test names to be executed. Worker 1 handles this request.',
            name: 'MTR_STANDALONE_TESTS')
        string(
            defaultValue: '1',
            description: 'MTR workers count for standalone tests',
            name: 'MTR_STANDALONE_TESTS_PARALLEL')
        booleanParam(
            defaultValue: true,
            description: 'Rerun aborted workers',
            name: 'ALLOW_ABORTED_WORKERS_RERUN')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
        copyArtifactPermission('percona-server-8.0-param-parallel-mtr');
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
                    echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
                    echo "Using instances with LABEL ${LABEL} for build and test stages"
                }
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                script {
                    BUILD_TRIGGER_BY = " (${currentBuild.getBuildCauses()[0].userId})"
                    if (BUILD_TRIGGER_BY == " (null)") {
                        BUILD_TRIGGER_BY = " "
                    }
                    currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}${BUILD_TRIGGER_BY} ${CUSTOM_BUILD_NAME}"
                }

                sh 'echo Prepare: \$(date -u "+%s")'

                validatePsBranch()
                setupTestSuitesSplit()

                script{
                    env.BUILD_TAG_BINARIES = "jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER_BINARIES}"
                    BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER_BINARIES
                    sh 'printenv'
                }
            }
        }
        stage('Build') {
            when {
                beforeAgent true
                expression { env.BUILD_NUMBER_BINARIES == '' }
            }
            agent { label LABEL }
            steps {
                script {
                    echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
                    echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
                }
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                checkoutSources()
                build("./docker/run-build")

                script {
                    boolean archive_public_url = false
                    BIN_FILE_NAME = sh(
                        script: 'ls sources/results/*.tar.gz | head -1',
                        returnStdout: true
                    ).trim()
                    LOG_FILE_NAME = sh(
                        script: 'ls build.log.gz | head -1',
                        returnStdout: true
                    ).trim()
                    if (BIN_FILE_NAME != "") {
                        uploadFileToS3("$BIN_FILE_NAME", "$BUILD_TAG", "binary.tar.gz")
                        sh "echo 'binary    - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG_BINARIES}/binary.tar.gz' >> public_url"
                        archive_public_url = true
                    } else {
                        echo 'Cannot find compiled archive log'
                        currentBuild.result = 'FAILURE'
                    }
                    if (LOG_FILE_NAME != "") {
                        uploadFileToS3("$LOG_FILE_NAME", "$BUILD_TAG", "build.log.gz")
                        sh "echo 'build log - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG_BINARIES}/build.log.gz' >> public_url"
                        archive_public_url = true
                        archiveArtifacts 'build.log.gz'
                        sh """
                            gunzip build.log.gz
                            ls | grep -xv "build.log\\|public_url" | xargs rm -rf
                        """
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
                stage('Test - 1') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_1_MTR_SUITES?.trim() || env.MTR_STANDALONE_TESTS?.trim() || env.CI_FS_MTR?.trim() == 'yes' || env.KEYRING_VAULT_MTR?.trim() == 'yes' || ZEN_FS_MTR_SUPPORTED && (env.ZEN_FS_MTR?.trim() == 'yes') ) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(1, "${WORKER_1_MTR_SUITES}", "${MTR_STANDALONE_TESTS}", true, env.CI_FS_MTR?.trim() == 'yes', env.KEYRING_VAULT_MTR?.trim() == 'yes', ZEN_FS_MTR_SUPPORTED && (env.ZEN_FS_MTR?.trim() == 'yes'))
                    }
                }
                stage('Test - 2') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_2_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(2, "${WORKER_2_MTR_SUITES}")
                    }
                }
                stage('Test - 3') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_3_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(3, "${WORKER_3_MTR_SUITES}")
                    }
                }
                stage('Test - 4') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_4_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(4, "${WORKER_4_MTR_SUITES}")
                    }
                }
                stage('Test - 5') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_5_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(5, "${WORKER_5_MTR_SUITES}")
                    }
                }
                stage('Test - 6') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_6_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(6, "${WORKER_6_MTR_SUITES}")
                    }
                }
                stage('Test - 7') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_7_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(7, "${WORKER_7_MTR_SUITES}")
                    }
                }
                stage('Test - 8') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_8_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(8, "${WORKER_8_MTR_SUITES}")
                    }
                }
            }
        }
    }
    post {
        always {
            triggerAbortedTestWorkersRerun()
            sh 'echo Finish: \$(date -u "+%s")'
        }
    }
}
