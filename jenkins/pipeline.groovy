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
            choices: 'ubuntu:artful\ncentos:6\ncentos:7\ni386/centos:6\nubuntu:trusty\nubuntu:xenial\nubuntu:bionic\ndebian:wheezy\ndebian:jessie',
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
            choices: '\n-DWITH_ASAN=ON\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON\n-DWITH_MSAN=ON\n-DWITH_UBSAN=ON\n-DWITH_VALGRIND=ON',
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
            choices: 'system\n/usr',
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
        label LABEL
    }
    options {
        compressBuildLog()
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Prepare') {
            steps {
                sh '''
                    echo Prepare: \$(date -u "+%s")
                '''
                git poll: true, branch: '5.7', url: 'https://github.com/Percona-Lab/ps-build'
                sh '''
                    git reset --hard
                    git clean -xdf
                    ./local/checkout
                    sg docker -c "
                        if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                            docker ps -q | xargs docker stop --time 1 || :
                        fi
                        docker pull perconalab/ps-build:\${DOCKER_OS//[:\\/]/-}
                    "
                '''
            }
        }
        stage('Build') {
            options { retry(2) }
            steps {
                sh '''
                    echo Build: \$(date -u "+%s")
                    sg docker -c "
                        ./docker/run-build ${DOCKER_OS}
                    " 2>&1 | tee build.log

                    sed -i -e '
                        s^/tmp/ps/^sources/^;
                        s^/tmp/results/^sources/^;
                        s^/xz/src/build_lzma/^/third_party/xz-4.999.9beta/^;
                    ' build.log
                    gzip -c build.log > build.log.gz
                '''
                archiveArtifacts 'build.log.gz'
                warnings canComputeNew: false, canResolveRelativePaths: false, categoriesPattern: '', defaultEncoding: '', excludePattern: '', healthy: '', includePattern: '', messagesPattern: '', parserConfigurations: [[parserName: 'GNU C Compiler 4 (gcc)', pattern: 'build.log']], unHealthy: ''
                stash includes: 'sources/results/*.tar.gz', name: 'binary'
            }
        }
        stage('Test') {
            options { retry(2) }
            steps {
                unstash 'binary'
                sh '''
                    echo Test: \$(date -u "+%s")
                    sg docker -c "
                        ./docker/run-test ${DOCKER_OS}
                    "
                '''
                archiveArtifacts 'sources/results/*.xml'
                step([$class: 'JUnitResultArchiver', testResults: 'sources/results/*.xml', healthScaleFactor: 1.0])
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
