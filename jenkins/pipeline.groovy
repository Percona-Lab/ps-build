AWS_CREDENTIALS_ID = '10ee734d-bbd1-4b4b-a611-5a2765ef9d47'

if (params.CLOUD == 'Hetzner') {
    LABEL = 'docker-x64'
    MICRO_LABEL = 'launcher-x64'
} else {
    // by default fallback to AWS
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
}

pipeline {
    agent {
        label MICRO_LABEL
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
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                  withCredentials([string(credentialsId: 'JNKPercona', variable: 'JNKPercona_token')]) {
                    sh 'echo Prepare: \$(date -u "+%s")'
                    echo 'Checking Percona Server branch version, JEN-913 prevent wrong version run'
                    sh '''#!/bin/bash -x
                        MY_BRANCH_BASE_MAJOR=5
                        MY_BRANCH_BASE_MINOR=7
                        GIT_REPO_LINK=${GIT_REPO}
                        if [[ "${GIT_REPO}" =~ post-eol|private|eol-dev ]]; then
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
                    git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                    sh '''#!/bin/bash -x
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
            agent { label MICRO_LABEL }
            steps {
                deleteDir()
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/build.log.gz ./build.log.gz
                        gunzip build.log.gz
                    '''
                }
                recordIssues enabledForFailure: true, tools: [gcc(pattern: 'build.log')]
            }
        }
        stage('Test') {
            options { retry(3) }
            agent { label LABEL }
            steps {
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    withCredentials([
                        string(credentialsId: 'MTR_VAULT_TOKEN', variable: 'MTR_VAULT_TOKEN'),
                        string(credentialsId: 'VAULT_V1_DEV_TOKEN', variable: 'VAULT_V1_DEV_TOKEN'),
                        string(credentialsId: 'VAULT_V2_DEV_TOKEN', variable: 'VAULT_V2_DEV_TOKEN')]) {
                            sh '''#!/bin/bash -x
                                git reset --hard
                                git clean -xdf
                                ls -la ./ || true
                                ls -la sources/ || true
                                ls -la sources/results/ || true
                                rm -rf sources/results/*
                                until aws s3 cp --no-progress s3://ps-build-cache/${BUILD_TAG}/binary.tar.gz ./sources/results/binary.tar.gz; do
                                    sleep 5
                                    mkdir -p sources/results
                                done

                                if [ -f /usr/bin/yum ]; then
                                    sudo yum -y install jq
                                else
                                    sudo apt-get install -y jq
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
                                        docker rm --force consul vault-prod-v1 vault-prod-v2 vault-dev-v1 vault-dev-v2 || :
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
            agent { label MICRO_LABEL }
            steps {
                deleteDir()
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws s3 sync --no-progress --exclude 'binary.tar.gz' s3://ps-build-cache/${BUILD_TAG}/ ./

                        echo "
                            binary    - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/binary.tar.gz
                            build log - https://s3.us-east-2.amazonaws.com/ps-build-cache/${BUILD_TAG}/build.log.gz
                        " > public_url
                    '''
                }
                script {
                    if (!(params.GIT_REPO =~ 'post-eol')) {
                        step([$class: 'JUnitResultArchiver', testResults: '**/*.xml', healthScaleFactor: 1.0])
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
