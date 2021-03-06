void build(String CMAKE_BUILD_TYPE) {
    script {
        sh 'echo Prepare: \$(date -u "+%s")'
        git branch: PS_BRANCH, url: 'https://github.com/Percona-Lab/ps-build'
        sh '''
            git reset --hard
            git clean -xdf
            rm -rf sources/results
        '''
        copyArtifacts filter: 'PIPELINE_BUILD_NUMBER', projectName: "${UPSTREAM_JOBNAME}/CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE},DOCKER_OS=centos:7", selector: specific(UPSTREAM_NUMBER)
        sh """
            PIPELINE_BUILD_NUMBER=\$(cat PIPELINE_BUILD_NUMBER)
            until aws s3 cp --no-progress s3://ps-build-cache/jenkins-percona-server-${PS_BRANCH}-pipeline-\${PIPELINE_BUILD_NUMBER}/binary.tar.gz ./sources/results/binary.tar.gz; do
                sleep 5
            done
            sudo ./docker/install-deps

            echo Test: \$(date -u "+%s")
            ulimit -a; env
            export DEFAULT_TESTING=yes
            export HOTBACKUP_TESTING=no
            export TOKUDB_ENGINES_MTR=no
            export CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
            export DOCKER_OS="CentOS-7"
            ./local/test-binary ./sources/results

            echo Archive test: \$(date -u "+%s")
            rm -rf ./sources/results/PS
            until aws s3 sync --no-progress --acl public-read --exclude 'binary.tar.gz' ./sources/results/ s3://ps-build-cache/${BUILD_TAG}/${CMAKE_BUILD_TYPE}/; do
                sleep 5
            done
        """
    }
}

pipeline {
    environment {
        PS_BRANCH = "5.7"
    }
    parameters {
        run(
            projectName: 'percona-server-5.7-trunk',
            description: '',
            name: 'UPSTREAM')
        string(
            defaultValue: 'main.fips',
            description: 'mysql-test-run.pl options, for options like: --big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    triggers {
        upstream threshold: hudson.model.Result.UNSTABLE, upstreamProjects: 'percona-server-5.7-trunk'
    }
    stages {
        stage('Test') {
            parallel {
                stage('Test RelWithDebInfo') {
                    options { retry(3) }
                    agent { label 'fips-centos-7-x64' }
                    steps {
                        build('RelWithDebInfo')
                    }
                }
                stage('Test Debug') {
                    options { retry(3) }
                    agent { label 'fips-centos-7-x64' }
                    steps {
                        build('Debug')
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
                '''
                step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
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
