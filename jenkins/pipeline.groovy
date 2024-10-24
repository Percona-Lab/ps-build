if (params.MTR_ARGS.contains('--big-test')) {
    LABEL = 'docker-32gb'
}

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-server',
            description: 'URL to percona-server repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: '5.7',
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
            choices: 'centos:6\ncentos:7\ncentos:8\ni386/centos:6\noraclelinux:9\nubuntu:xenial\nubuntu:bionic\nubuntu:focal\nubuntu:jammy\ndebian:stretch\ndebian:buster\ndebian:bullseye\ndebian:bookworm\namazonlinux:2',
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
            choices: 'ON\nOFF',
            description: 'Compile TokuDB engine',
            name: 'WITH_TOKUDB')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile RocksDB engine',
            name: 'WITH_ROCKSDB')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build embedded server',
            name: 'WITH_EMBEDDED_SERVER')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build rapid development cycle plugins',
            name: 'WITH_RAPID')
        choice(
            choices: 'system\nbundled',
            description: 'Type of SSL support',
            name: 'WITH_SSL')
        choice(
            choices: 'ON\nOFF',
            description: 'Whether to build with support for keyring_vault Plugin',
            name: 'WITH_KEYRING_VAULT')
        choice(
            choices: '\n-DWITHOUT_PERFSCHEMA_STORAGE_ENGINE=ON',
            description: 'Disable Performance Schema',
            name: 'PERFSCHEMA_OPTS')
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
            description: 'Run mysql-test-run.pl --suite tokudb.backup',
            name: 'HOTBACKUP_TESTING')
        choice(
            choices: 'yes\nno',
            description: 'Run mtr --suite=engines/iuds,engines/funcs --mysqld=--default-storage-engine=tokudb',
            name: 'TOKUDB_ENGINES_MTR')
        string(
            defaultValue: '--unit-tests-report',
            description: 'TokuDB specific mtr args',
            name: 'TOKUDB_ENGINES_MTR_ARGS')
        choice(
            choices: 'yes\nno',
            description: 'Run mtr --suite=engines/iuds,engines/funcs --mysqld=--default-storage-engine=rocksdb',
            name: 'ROCKSDB_ENGINES_MTR')
        string(
            defaultValue: '--unit-tests-report',
            description: 'RocksDB specific mtr args',
            name: 'ROCKSDB_ENGINES_MTR_ARGS')

        string(
            defaultValue: '--unit-tests-report',
            description: 'mysql-test-run.pl options, for options like: --big-test --nounit-tests --unit-tests-report',
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
            choices: 'yes\nno',
            description: 'Run case-insensetive MTR tests',
            name: 'CI_FS_MTR')
        choice(
            choices: 'docker\ndocker-32gb',
            description: 'Run build on specified instance type',
            name: 'LABEL')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Build') {
            options { retry(3) }
            agent { label LABEL }
            steps {
                script {
                    currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '10ee734d-bbd1-4b4b-a611-5a2765ef9d47', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                  withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
                    sh 'echo Prepare: \$(date -u "+%s")'
                    echo 'Checking Percona Server branch version, JEN-913 prevent wrong version run'
                    sh '''
                        MY_BRANCH_BASE_MAJOR=5
                        MY_BRANCH_BASE_MINOR=7
                        GIT_REPO_LINK=${GIT_REPO}
                        if [[ "${GIT_REPO}" =~ post-eol|private ]]; then
                            GIT_REPO_LINK=$(echo ${GIT_REPO} | sed -e "s|github|x-access-token:${JNKPercona_token}@github|g")
                        fi
                        RAW_VERSION_LINK=$(echo ${GIT_REPO_LINK%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
                        REPLY=$(curl -Is ${RAW_VERSION_LINK}/${BRANCH}/MYSQL_VERSION | head -n 1 | awk '{print $2}')
                        if [[ ${REPLY} != 200 ]]; then
                            curl ${RAW_VERSION_LINK}/${BRANCH}/VERSION -o ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                        else
                            curl ${RAW_VERSION_LINK}/${BRANCH}/MYSQL_VERSION -o ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                        fi
                        source ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                        if [[ ${MYSQL_VERSION_MAJOR} -ne ${MY_BRANCH_BASE_MAJOR} || ${MYSQL_VERSION_MINOR} -ne ${MY_BRANCH_BASE_MINOR} ]] ; then
                            echo "Are you trying to build wrong branch?"
                            echo "You are trying to build ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR} instead of ${MY_BRANCH_BASE_MAJOR}.${MY_BRANCH_BASE_MINOR}!"
                            rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                            exit 1
                        fi
                        rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                    '''
                    git branch: '5.7', url: 'https://github.com/Percona-Lab/ps-build'
                    sh '''
                        git reset --hard
                        git clean -xdf
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
        stage('Archive Build') {
            options { retry(3) }
            agent { label 'micro-amazon' }
            steps {
                deleteDir()
                sh '''
                    aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/build.log.gz ./build.log.gz
                    gunzip build.log.gz
                '''
                recordIssues enabledForFailure: true, tools: [gcc(pattern: 'build.log')]
            }
        }
        stage('Test') {
            options { retry(3) }
            agent { label LABEL }
            steps {
                git branch: '5.7', url: 'https://github.com/Percona-Lab/ps-build'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '10ee734d-bbd1-4b4b-a611-5a2765ef9d47', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    withCredentials([
                        string(credentialsId: 'MTR_VAULT_TOKEN', variable: 'MTR_VAULT_TOKEN'),
                        string(credentialsId: 'VAULT_V1_DEV_TOKEN', variable: 'VAULT_V1_DEV_TOKEN'),
                        string(credentialsId: 'VAULT_V2_DEV_TOKEN', variable: 'VAULT_V2_DEV_TOKEN')]) {
                            sh '''
                                git reset --hard
                                git clean -xdf
                                rm -rf sources/results
                                until aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz ./sources/results/binary.tar.gz; do
                                    sleep 5
                                done

                                sudo yum -y install jq

                                if [[ \$CI_FS_MTR == 'yes' ]]; then
                                    if [[ ! -f /mnt/ci_disk_\$CMAKE_BUILD_TYPE.img ]] && [[ -z \$(mount | grep /mnt/ci_disk_dir_\$CMAKE_BUILD_TYPE) ]]; then
                                        sudo dd if=/dev/zero of=/mnt/ci_disk_\$CMAKE_BUILD_TYPE.img bs=1G count=10
                                        sudo /sbin/mkfs.vfat /mnt/ci_disk_\$CMAKE_BUILD_TYPE.img
                                        sudo mkdir -p /mnt/ci_disk_dir_\$CMAKE_BUILD_TYPE
                                        sudo mount -o loop -o uid=27 -o gid=27 -o check=r /mnt/ci_disk_\$CMAKE_BUILD_TYPE.img /mnt/ci_disk_dir_\$CMAKE_BUILD_TYPE
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
        stage('Archive') {
            options { retry(3) }
            agent { label 'micro-amazon' }
            steps {
                deleteDir()
                sh '''
                    aws s3 sync --no-progress --exclude 'binary.tar.gz' s3://ps-build-cache/${BUILD_TAG}/ ./

                    echo "
                        binary    - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/binary.tar.gz
                        build log - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/build.log.gz
                    " > public_url
                '''
                script {
                    if (!(params.GIT_REPO ==~ '.*(post-eol|private).*')) {
                        step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                    }
                }
                archiveArtifacts 'build.log.gz,*.xml,public_url'
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
