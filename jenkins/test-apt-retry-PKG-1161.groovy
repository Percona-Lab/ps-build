// Test pipeline for PKG-1161: Validate apt-get retry logic on Hetzner launcher-x64
// This job tests the retry mechanism for apt-get commands to handle lock contentions

def MICRO_LABEL = 'launcher-x64'  // Hetzner launcher node

pipeline {
    agent {
        label MICRO_LABEL
    }
    options {
        skipDefaultCheckout()
        timeout(time: 10, unit: 'MINUTES')
    }
    stages {
        stage('Test 1: apt-get WITHOUT retry (baseline - expected to occasionally fail)') {
            steps {
                script {
                    echo "=== Testing apt-get WITHOUT retry logic ==="
                    echo "NODE_NAME = ${env.NODE_NAME}"
                    echo "This may fail if apt-daily service is running"

                    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                        sh '''#!/bin/bash
                            set +e
                            echo "Attempting apt-get update WITHOUT retry..."
                            if [ -f /usr/bin/apt ]; then
                                sudo apt-get update
                                EXIT_CODE=$?
                                if [ $EXIT_CODE -ne 0 ]; then
                                    echo "FAILED: apt-get update failed with exit code $EXIT_CODE"
                                    echo "This is expected behavior if apt-daily service holds the lock"
                                else
                                    echo "SUCCESS: apt-get update succeeded (no lock contention at this time)"
                                fi
                            else
                                echo "SKIP: Not a Debian-based system"
                            fi
                        '''
                    }
                }
            }
        }

        stage('Test 2: apt-get WITH retry logic (should always succeed)') {
            steps {
                script {
                    echo "=== Testing apt-get WITH retry logic ==="
                    echo "This should succeed even if apt-daily service is running"

                    sh '''#!/bin/bash
                        set -e
                        echo "Attempting apt-get update WITH retry logic..."
                        if [ -f /usr/bin/apt ]; then
                            retry_count=0
                            until sudo DEBIAN_FRONTEND=noninteractive apt-get update || [ $retry_count -eq 30 ]; do
                                retry_count=$((retry_count+1))
                                echo "Retry attempt $retry_count/30 (waiting for apt lock to be released)"
                                sleep 1
                            done

                            if [ $retry_count -eq 30 ]; then
                                echo "FAILED: apt-get update failed after 30 retries"
                                exit 1
                            else
                                if [ $retry_count -gt 0 ]; then
                                    echo "SUCCESS: apt-get update succeeded after $retry_count retries"
                                else
                                    echo "SUCCESS: apt-get update succeeded on first attempt"
                                fi
                            fi
                        else
                            echo "SKIP: Not a Debian-based system"
                        fi
                    '''
                }
            }
        }

        stage('Test 3: apt-get install WITH retry logic') {
            steps {
                script {
                    echo "=== Testing apt-get install jq WITH retry logic ==="

                    sh '''#!/bin/bash
                        set -e
                        if [ -f /usr/bin/apt ]; then
                            echo "Attempting apt-get install jq WITH retry logic..."
                            retry_count=0
                            until sudo DEBIAN_FRONTEND=noninteractive apt-get install -y jq || [ $retry_count -eq 30 ]; do
                                retry_count=$((retry_count+1))
                                echo "Retry attempt $retry_count/30 (waiting for apt lock to be released)"
                                sleep 1
                            done

                            if [ $retry_count -eq 30 ]; then
                                echo "FAILED: apt-get install failed after 30 retries"
                                exit 1
                            else
                                if [ $retry_count -gt 0 ]; then
                                    echo "SUCCESS: apt-get install succeeded after $retry_count retries"
                                else
                                    echo "SUCCESS: apt-get install succeeded on first attempt"
                                fi
                            fi

                            # Verify jq installed
                            which jq && echo "jq is installed: $(jq --version)"
                        else
                            echo "SKIP: Not a Debian-based system"
                        fi
                    '''
                }
            }
        }

        stage('System Info') {
            steps {
                script {
                    echo "=== System Information ==="
                    sh '''#!/bin/bash
                        echo "Hostname: $(hostname)"
                        echo "OS: $(cat /etc/os-release | grep PRETTY_NAME || echo 'Unknown')"
                        echo "Kernel: $(uname -r)"
                        echo "apt-daily services status:"
                        systemctl status apt-daily.service apt-daily.timer apt-daily-upgrade.service apt-daily-upgrade.timer 2>/dev/null || echo "apt-daily services not found or not accessible"
                    '''
                }
            }
        }
    }
    post {
        always {
            echo "Test completed"
        }
        success {
            echo "SUCCESS: All retry logic tests passed!"
        }
        failure {
            echo "FAILURE: Retry logic tests failed"
        }
    }
}
