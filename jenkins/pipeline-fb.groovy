pipeline_timeout = 24

if (
    (params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) ||
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON'))
    ) { pipeline_timeout = 48 }

if (params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON'))
    { pipeline_timeout = 144 }

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/facebook/mysql-5.6',
            description: 'URL to repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'fb-mysql-8.0.13',
            description: 'Tag/Branch for repository',
            name: 'BRANCH')
        choice(
            choices: 'centos:7\ncentos:8\nubuntu:bionic\nubuntu:focal',
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
        string(
            defaultValue: '',
            description: 'cmake options',
            name: 'CMAKE_OPTS')
        string(
            defaultValue: '',
            description: 'make options, like VERBOSE=1',
            name: 'MAKE_OPTS')
        booleanParam(
            defaultValue: false, 
            description: 'Compile MySQL server with BoringSSL', 
            name: 'WITH_BORINGSSL') 
        choice(
            choices: 'yes\nno',
            description: 'Run mysql-test-run.pl',
            name: 'DEFAULT_TESTING')
        string(
            defaultValue: '--unit-tests-report',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
        choice(
            choices: 'docker-32gb',
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
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '863baec1-9548-4f2f-917d-13f2191d246c', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh 'echo Prepare: \$(date -u "+%s")'
                            echo 'Checking Percona Server branch version, JEN-913 prevent wrong version run'
                            sh '''
                                MY_BRANCH_BASE_MAJOR=fb-mysql-8
                                MY_BRANCH_BASE_MINOR=0.13
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
                        withCredentials([string(credentialsId: 'MTR_VAULT_TOKEN', variable: 'MTR_VAULT_TOKEN')]) {
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c8b933cd-b8ca-41d5-b639-33fe763d3f68', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
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
                                    aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
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
