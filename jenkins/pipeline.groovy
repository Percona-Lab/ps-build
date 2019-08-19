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
        pipeline_timeout = 13
      }

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-server',
            description: 'URL to percona-server repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-server repository',
            name: 'BRANCH')
        string(
            defaultValue: '',
            description: 'URL to forked PerconaFT repository',
            name: 'PERCONAFT_REPO')
        string(
            defaultValue: '',
            description: 'Tag/Branch for PerconaFT repository',
            name: 'PERCONAFT_BRANCH')
        string(
            defaultValue: '',
            description: 'URL to forked Percona-TokuBackup repository',
            name: 'TOKUBACKUP_REPO')
        string(
            defaultValue: '',
            description: 'Tag/Branch for Percona-TokuBackup repository',
            name: 'TOKUBACKUP_BRANCH')
        choice(
            choices: 'centos:6\ncentos:7\nubuntu:xenial\nubuntu:bionic\nubuntu:disco\ndebian:stretch\ndebian:buster\nroboxes-rhel8',
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
            choices: 'ON\nOFF',
            description: 'Whether to build with support for X Plugin',
            name: 'WITH_MYSQLX')
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
            defaultValue: '',
            description: 'TokuDB specific mtr args',
            name: 'TOKUDB_ENGINES_MTR_ARGS')
        string(
            defaultValue: '--unit-tests-report',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
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

                    sh 'echo Prepare: \$(date -u "+%s")'
                    echo 'Checking Percona Server branch version, JEN-913 prevent wrong version run'
                    sh '''
                        MY_BRANCH_BASE_MAJOR=8
                        MY_BRANCH_BASE_MINOR=0
                        RAW_VERSION_LINK=$(echo ${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
                        wget ${RAW_VERSION_LINK}/${BRANCH}/VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
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
                    sh '''
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf sources
                        ./local/checkout

                        echo Build: \$(date -u "+%s")
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
            agent { label 'micro-amazon' }
            steps {
                timeout(time: 60, unit: 'MINUTES')  {
                    retry(3) {
                        deleteDir()
                        sh '''
                            aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/build.log.gz ./build.log.gz
                            gunzip build.log.gz
                        '''
                        warnings canComputeNew: false, canResolveRelativePaths: false, categoriesPattern: '', defaultEncoding: '', excludePattern: '', healthy: '', includePattern: '', messagesPattern: '', parserConfigurations: [[parserName: 'GNU C Compiler 4 (gcc)', pattern: 'build.log']], unHealthy: ''
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
                        withCredentials([string(credentialsId: 'MTR_VAULT_TOKEN', variable: 'MTR_VAULT_TOKEN')]) {
                            sh '''
                                sudo git reset --hard
                                sudo git clean -xdf
                                rm -rf sources/results
                                sudo git -C sources reset --hard || :
                                sudo git -C sources clean -xdf   || :

                                until aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz ./sources/results/binary.tar.gz; do
                                    sleep 5
                                done

                                echo Test: \$(date -u "+%s")
                                sg docker -c "
                                    if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                        docker ps -q | xargs docker stop --time 1 || :
                                    fi
                                    ulimit -a
                                    ./docker/run-test ${DOCKER_OS}
                                "

                                echo Archive test: \$(date -u "+%s")
                                gzip sources/results/*.output
                                until aws s3 sync --no-progress --acl public-read --exclude 'binary.tar.gz' ./sources/results/ s3://ps-build-cache/${BUILD_TAG}/; do
                                    sleep 5
                                done
                            '''
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
                sh '''
                    aws s3 sync --no-progress --exclude 'binary.tar.gz' s3://ps-build-cache/${BUILD_TAG}/ ./

                    echo "
                        binary    - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/binary.tar.gz
                        build log - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/build.log.gz
                        mtr log   - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/mtr.output.gz
                    " > public_url
                '''
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts 'build.log.gz,*.xml,*.output.gz,public_url'
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
