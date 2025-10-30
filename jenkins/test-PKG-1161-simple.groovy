// Simple test for PKG-1161: apt-get retry logic on Hetzner launcher-x64
pipeline {
    agent { label 'launcher-x64' }
    options {
        skipDefaultCheckout()
        timeout(time: 5, unit: 'MINUTES')
    }
    stages {
        stage('Test apt-get with retry') {
            steps {
                sh '''#!/bin/bash
                    echo "Testing apt-get retry logic..."
                    echo "Node: $(hostname)"

                    if [ -f /usr/bin/apt ]; then
                        until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
                            sleep 1
                            echo "Retrying apt-get update..."
                        done
                        echo "SUCCESS: apt-get update completed"
                    else
                        echo "Not a Debian system, skipping"
                    fi
                '''
            }
        }
    }
}
