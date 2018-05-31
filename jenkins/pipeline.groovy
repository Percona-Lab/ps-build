pipeline {
    parameters {
        string(
            defaultValue: '',
            description: 'URL to percona-server repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '5.7',
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
            choices: 'ubuntu:artful\ncentos:6\ncentos:7\ni386/centos:6\nubuntu:trusty\nubuntu:xenial\nubuntu:bionic\ndebian:jessie',
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
            description: 'Run mysql-test-run.pl engines/iuds,engines/funcs --mysqld=--default-storage-engine=tokudb',
            name: 'TOKUDB_ENGINES_MTR')
        string(
            defaultValue: '--unit-tests-report',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-tests --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
        choice(
            choices: 'docker\ndocker-32gb',
            description: 'Run build on specified instance type',
            name: 'LABEL')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        retry(2)
        compressBuildLog()
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Build') {
            agent { label LABEL }
            steps {
                sh 'echo Prepare: \$(date -u "+%s")'
                git poll: true, branch: '5.7', url: 'https://github.com/Percona-Lab/ps-build'
                sh '''
                    git reset --hard
                    git clean -xdf
                    rm -rf sources/results
                    ./local/checkout

                    echo Build: \$(date -u "+%s")
                    sg docker -c "
                        ./docker/run-build ${DOCKER_OS}
                    " 2>&1 | tee build.log

                    echo Archive build: \$(date -u "+%s")
                    sed -i -e '
                        s^/tmp/ps/^sources/^;
                        s^/tmp/results/^sources/^;
                        s^/xz/src/build_lzma/^/third_party/xz-4.999.9beta/^;
                    ' build.log
                    gzip build.log

                    until aws s3 cp --no-progress build.log.gz s3://ps-build-cache/${BUILD_TAG}/build.log.gz; do
                        sleep 5
                    done
                    until aws s3 cp --no-progress sources/results/*.tar.gz s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz; do
                        sleep 5
                    done
                '''
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
                warnings canComputeNew: false, canResolveRelativePaths: false, categoriesPattern: '', defaultEncoding: '', excludePattern: '', healthy: '', includePattern: '', messagesPattern: '', parserConfigurations: [[parserName: 'GNU C Compiler 4 (gcc)', pattern: 'build.log']], unHealthy: ''
            }
        }
        stage('Test') {
            agent { label LABEL }
            steps {
                sh '''
                    git reset --hard
                    git clean -xdf
                    rm -rf sources/results
                    until aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz ./sources/results/binary.tar.gz; do
                        sleep 5
                    done

                    echo Test: \$(date -u "+%s")
                    sg docker -c "
                        ./docker/run-test ${DOCKER_OS}
                    "

                    echo Archive test: \$(date -u "+%s")
                    gzip sources/results/*.output
                    until aws s3 sync --no-progress --exclude 'binary.tar.gz' ./sources/results/ s3://ps-build-cache/${BUILD_TAG}/; do
                        sleep 5
                    done
                '''
            }
        }
        stage('Archive') {
            options { retry(3) }
            agent { label 'micro-amazon' }
            steps {
                deleteDir()
                sh 'aws s3 sync --no-progress s3://ps-build-cache/${BUILD_TAG}/ ./'
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts 'build.log.gz,*.xml,*.output.gz'
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
