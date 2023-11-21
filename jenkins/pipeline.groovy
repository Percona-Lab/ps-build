pipeline_timeout = 10

if (
    (params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) ||
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON'))
    ) { pipeline_timeout = 24 }

if (params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON'))
    { pipeline_timeout = 144 }

if (
    ((params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) &&
    (params.ANALYZER_OPTS.contains('-DWITH_ASAN_SCOPE=ON')) &&
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON'))) ||
    ((params.MTR_ARGS.contains('--big-test')) || (params.MTR_ARGS.contains('--only-big-test')))
    ) {
        LABEL = 'docker-32gb'
        pipeline_timeout = 20
      }
 
if (
    (params.ZEN_FS_MTR == 'yes') &&
    (params.DOCKER_OS == 'ubuntu:jammy')
    ) { 
        LABEL = 'docker-32gb-bullseye'
        pipeline_timeout = 22
      }

if (
    (params.ZEN_FS_MTR == 'yes') &&
    (params.DOCKER_OS == 'debian:bullseye')
    ) {
        LABEL = 'docker-32gb-bullseye'
        pipeline_timeout = 22
      }

if (
    (params.ZEN_FS_MTR == 'yes') &&
    (params.DOCKER_OS == 'oraclelinux:9')
    ) {
        LABEL = 'docker-32gb-bullseye'
        pipeline_timeout = 22
      }

pipeline {
    parameters {
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
            description: 'URL to forked PerconaFT repository',
            name: 'PERCONAFT_REPO',
            trim: true)
        string(
            defaultValue: '',
            description: 'Tag/Branch for PerconaFT repository',
            name: 'PERCONAFT_BRANCH',
            trim: true)
        string(
            defaultValue: '',
            description: 'URL to forked Percona-TokuBackup repository',
            name: 'TOKUBACKUP_REPO',
            trim: true)
        string(
            defaultValue: '',
            description: 'Tag/Branch for Percona-TokuBackup repository',
            name: 'TOKUBACKUP_BRANCH',
            trim: true)
        choice(
            choices: 'centos:7\ncentos:8\noraclelinux:9\nubuntu:bionic\nubuntu:focal\nubuntu:jammy\ndebian:buster\ndebian:bullseye\ndebian:bookworm',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: '/usr/bin/cmake',
            description: 'path to cmake binary',
            name: 'JOB_CMAKE')
        choice(
            choices: 'default',
            description: 'compiler version',
            name: 'COMPILER')
        choice(
            choices: 'RelWithDebInfo\nDebug',
            description: 'Type of build to produce',
            name: 'CMAKE_BUILD_TYPE')
        choice(
            choices: '\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON\n-DWITH_ASAN=ON\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON -DWITH_UBSAN=ON\n-DWITH_ASAN=ON -DWITH_UBSAN=ON\n-DWITH_UBSAN=ON\n-DWITH_MSAN=ON\n-DWITH_VALGRIND=ON',
            description: 'Enable code checking',
            name: 'ANALYZER_OPTS')
        choice(
            choices: 'OFF\nON',
            description: 'Compile TokuDB engine',
            name: 'WITH_TOKUDB')
        choice(
            choices: 'OFF\nON',
            description: 'Compile RocksDB engine',
            name: 'WITH_ROCKSDB')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build MySQL Router',
            name: 'WITH_ROUTER')
	choice(
            choices: 'OFF\nON',
            description: 'Whether to build with Coverage',
            name: 'WITH_GCOV')
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
            choices: 'yes\nno',
            description: 'Run mysql-test-run.pl',
            name: 'DEFAULT_TESTING')
        choice(
            choices: 'yes\nno',
            description: 'Run mysql-test-run.pl --suite tokudb_backup',
            name: 'HOTBACKUP_TESTING')
        choice(
            choices: 'yes\nno',
            description: 'Run mtr --suite=engines/iuds,engines/funcs --mysqld=--default-storage-engine=tokudb',
            name: 'TOKUDB_ENGINES_MTR')
        string(
            defaultValue: '',
            description: 'TokuDB specific mtr args',
            name: 'TOKUDB_ENGINES_MTR_ARGS')
        choice(
            choices: 'yes\nno',
            description: 'Run ZenFS MTR tests',
            name: 'ZEN_FS_MTR')
        choice(
            choices: 'yes\nno',
            description: 'Run case-insensetive MTR tests',
            name: 'CI_FS_MTR')
        choice(
            choices: 'yes\nno',
            description: 'Run MTR tests with --ps-protocol',
            name: 'WITH_PS_PROTOCOL')

        string(
            defaultValue: '--unit-tests-report',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
        choice(
            choices: 'yes\nno',
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
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Build') {
            agent { label LABEL }
            steps {
                timeout(time: 180, unit: 'MINUTES')  {
                    retry(3) {
                        script {
                            currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                        }
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c8b933cd-b8ca-41d5-b639-33fe763d3f68', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh 'echo Prepare: \$(date -u "+%s")'
                            echo 'Checking Percona Server branch version, JEN-913 prevent wrong version run'
                            sh '''#!/bin/bash
                                MY_BRANCH_BASE_MAJOR=8
                                MY_BRANCH_BASE_MINOR=0
                                RAW_VERSION_LINK=$(echo ${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
                                REPLY=$(curl -Is ${RAW_VERSION_LINK}/${BRANCH}/MYSQL_VERSION | head -n 1 | awk '{print $2}')
                                if [[ ${REPLY} != 200 ]]; then
                                    wget ${RAW_VERSION_LINK}/${BRANCH}/VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                                else
                                    wget ${RAW_VERSION_LINK}/${BRANCH}/MYSQL_VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                                fi
                                source ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                                if [[ ${MYSQL_VERSION_MAJOR} -lt ${MY_BRANCH_BASE_MAJOR} ]] ; then
                                    echo "Are you trying to build wrong branch?"
                                    echo "You are trying to build ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR} instead of ${MY_BRANCH_BASE_MAJOR}.${MY_BRANCH_BASE_MINOR}!"
                                    rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                                    exit 1
                                fi
                                rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                            '''
                            git branch: '8.0', url: 'https://github.com/Percona-Lab/ps-build'
                            sh '''#!/bin/bash
                                # sudo is needed for better node recovery after compilation failure
                                # if building failed on compilation stage directory will have files owned by docker user
                                sudo git reset --hard
                                sudo git clean -xdf
                                sudo rm -rf sources
                                ./local/checkout

                                echo Build: \$(date -u "+%s")
                                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                                sg docker -c "
                                    if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                        docker ps -q | xargs docker stop --time 1 || :
                                    fi
                                    ./docker/run-build ${DOCKER_OS}
                                " 2>&1 | tee build.log
                                echo Archive build: \$(date -u "+%s")
                                sed -i -e '
                                    s^/tmp/ps/^sources/^;
                                    s^/tmp/results/^sources/^;
                                    s^/xz/src/build_lzma/^/third_party/xz-4.999.9beta/^;
                                ' build.log
                                gzip build.log

                                if [[ -f build.log.gz ]]; then
                                    until aws s3 cp --no-progress --acl public-read build.log.gz s3://ps-build-cache/${BUILD_TAG}/build.log.gz; do
                                        sleep 5
                                    done
                                fi

                                if [[ -f \$(ls sources/results/*.tar.gz | head -1) ]]; then
                                    until aws s3 cp --no-progress --acl public-read sources/results/*.tar.gz s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz; do
                                        sleep 5
                                    done
                                else
                                    echo cannot find compiled archive
                                    exit 1
                                fi
                            '''
                        }
                    }
                }
            }
        }
        stage('Archive Build') {
            agent { label 'micro-amazon' }
            steps {
                timeout(time: 60, unit: 'MINUTES')  {
                    retry(3) {
                        deleteDir()
                        sh '''
                            aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/build.log.gz ./build.log.gz
                            gunzip build.log.gz
                        '''
                        recordIssues enabledForFailure: true, tools: [gcc(pattern: 'build.log')]
                    }
                }
            }
        }
        stage('Test') {
            agent { label LABEL }
            steps {
                timeout(time: pipeline_timeout, unit: 'HOURS')  {
                    retry(3) {
                        git branch: '8.0', url: 'https://github.com/Percona-Lab/ps-build'
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c8b933cd-b8ca-41d5-b639-33fe763d3f68', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            withCredentials([
                                string(credentialsId: 'VAULT_V1_DEV_ROOT_TOKEN', variable: 'VAULT_V1_DEV_ROOT_TOKEN'),
                                string(credentialsId: 'VAULT_V2_DEV_ROOT_TOKEN', variable: 'VAULT_V2_DEV_ROOT_TOKEN')]) {
                                sh '''#!/bin/bash
                                    sudo git reset --hard
                                    sudo git clean -xdf
                                    rm -rf sources/results
                                    sudo git -C sources reset --hard || :
                                    sudo git -C sources clean -xdf   || :

                                    until aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz ./sources/results/binary.tar.gz; do
                                        sleep 5
                                    done
                                    if [ -f /usr/bin/yum ]; then
                                        sudo yum -y install jq gflags-devel
                                    else
                                        sudo apt-get install -y jq libgflags-dev libjemalloc-dev
                                    fi

                                    if [[ \$CI_FS_MTR == 'yes' ]]; then
                                        if [[ ! -f /mnt/ci_disk_\$CMAKE_BUILD_TYPE.img ]] && [[ -z \$(mount | grep /mnt/ci_disk_dir_\$CMAKE_BUILD_TYPE) ]]; then
                                            sudo dd if=/dev/zero of=/mnt/ci_disk_\$CMAKE_BUILD_TYPE.img bs=1G count=10
                                            sudo /sbin/mkfs.vfat /mnt/ci_disk_\$CMAKE_BUILD_TYPE.img
                                            sudo mkdir -p /mnt/ci_disk_dir_\$CMAKE_BUILD_TYPE
                                            sudo mount -o loop -o uid=1001 -o gid=1001 -o check=r /mnt/ci_disk_\$CMAKE_BUILD_TYPE.img /mnt/ci_disk_dir_\$CMAKE_BUILD_TYPE
                                        fi
                                    fi

                                    echo Test: \$(date -u "+%s")
                                    aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                                    sg docker -c "
                                        if [ \$(docker ps -a -q | wc -l) -ne 0 ]; then
                                            docker ps -a -q | xargs docker stop --time 1 || :
                                            docker rm --force consul vault-prod-v{1..2} vault-dev-v{1..2} || :
                                        fi
                                        ulimit -a
                                        ./docker/run-test ${DOCKER_OS}
                                    "

                                    echo Archive test: \$(date -u "+%s")
                                    until aws s3 sync --no-progress --acl public-read --exclude 'binary.tar.gz' ./sources/results/ s3://ps-build-cache/${BUILD_TAG}/; do
                                        sleep 5
                                    done
                                '''
                            }
                        }
                    }
                }
            }
        }
        stage('Archive') {
            agent { label 'micro-amazon' }
            steps {
                retry(3) {
                deleteDir()
                sh '''#!/bin/bash
                    aws s3 sync --no-progress --exclude 'binary.tar.gz' s3://ps-build-cache/${BUILD_TAG}/ ./

                    echo "
                        binary    - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/binary.tar.gz
                        build log - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/build.log.gz
                    " > public_url
                '''
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts 'build.log.gz,*.xml,public_url,coverage.txt'
                }
            }
        }
    }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}
