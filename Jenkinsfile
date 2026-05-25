pipeline {

    agent any

    environment {
        APP_NAME        = 'sih-notifications'
        DOCKER_HUB_USER = 'hichamazeroual2002'
        IMAGE_NAME      = "${DOCKER_HUB_USER}/${APP_NAME}"
        IMAGE_TAG       = "${BUILD_NUMBER}"
        SONAR_PROJECT   = 'sih-notifications'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
    }

    stages {

        // ─────────────────────────────────────────
        // STAGE 1 — Checkout
        // ─────────────────────────────────────────
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_AUTHOR      = sh(script: 'git log -1 --pretty=%an',     returnStdout: true).trim()
                    env.GIT_MSG         = sh(script: 'git log -1 --pretty=%B',      returnStdout: true).trim()
                    env.BRANCH          = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                }
                echo "Branch  : ${env.BRANCH}"
                echo "Commit  : ${env.GIT_COMMIT_SHORT}"
                echo "Author  : ${env.GIT_AUTHOR}"
                echo "Message : ${env.GIT_MSG}"
            }
        }

        // ─────────────────────────────────────────
        // STAGE 2 — Build
        // ─────────────────────────────────────────
        stage('Build') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw clean compile -q'
            }
        }

        // ─────────────────────────────────────────
        // STAGE 3 — Tests unitaires
        // ─────────────────────────────────────────
        stage('Unit Tests') {
            steps {
                sh './mvnw test -q'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        // ─────────────────────────────────────────
        // STAGE 4 — SonarQube Analysis
        // ─────────────────────────────────────────
        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        ./mvnw sonar:sonar \\
                            -Dsonar.projectKey=${SONAR_PROJECT} \\
                            -Dsonar.projectName='SIH Notifications' \\
                            -Dsonar.java.coveragePlugin=jacoco \\
                            -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \\
                            -q
                    """
                }
            }
        }

        // ─────────────────────────────────────────
        // STAGE 5 — Quality Gate
        // ─────────────────────────────────────────
        stage('Quality Gate') {
            steps {
                timeout(time: 3, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        // ─────────────────────────────────────────
        // STAGE 6 — Package JAR
        // ─────────────────────────────────────────
        stage('Package') {
            steps {
                sh './mvnw package -DskipTests -q'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }

        // ─────────────────────────────────────────
        // STAGE 7 — Docker Build
        // ─────────────────────────────────────────
        stage('Docker Build') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} -t ${IMAGE_NAME}:latest --no-cache ."
            }
        }

        // ─────────────────────────────────────────
        // STAGE 8 — Trivy Security Scan
        // ─────────────────────────────────────────
        stage('Trivy Scan') {
            steps {
                sh """
                    trivy image \\
                        --exit-code 1 \\
                        --severity CRITICAL,HIGH \\
                        --ignorefile .trivyignore \\
                        --no-progress \\
                        --format table \\
                        ${IMAGE_NAME}:${IMAGE_TAG}
                """
            }
            post {
                always {
                    sh """
                        trivy image \\
                            --exit-code 0 \\
                            --format json \\
                            --output trivy-report.json \\
                            ${IMAGE_NAME}:${IMAGE_TAG}
                    """
                    archiveArtifacts artifacts: 'trivy-report.json', allowEmptyArchive: true
                }
            }
        }

        // ─────────────────────────────────────────
        // STAGE 9 — Docker Push (Docker Hub)
        // ─────────────────────────────────────────
        stage('Docker Push') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-credentials',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh """
                        echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                        docker push ${IMAGE_NAME}:latest
                        docker logout
                    """
                }
            }
        }

        // ─────────────────────────────────────────
        // STAGE 10 — Deploy (Docker local)
        // ─────────────────────────────────────────
        stage('Deploy') {
            steps {
                sh """
                    docker stop ${APP_NAME} || true
                    docker rm   ${APP_NAME} || true
                    docker run -d \
                        --name ${APP_NAME} \
                        --restart unless-stopped \
                        -p 8082:8081 \
                        -e SPRING_PROFILES_ACTIVE=prod \
                        ${IMAGE_NAME}:${IMAGE_TAG}
                """
            }
        }

        // ─────────────────────────────────────────
        // STAGE 11 — Health Check
        // ─────────────────────────────────────────
        stage('Health Check') {
            steps {
                script {
                    retry(6) {
                        sleep(time: 10, unit: 'SECONDS')
                        sh "curl -sf http://host.docker.internal:8082/actuator/health | grep -q '\"status\":\"UP\"'"
                    }
                    echo 'Service UP ✅'
                }
            }
        }

        // ─────────────────────────────────────────
        // STAGE 12 — Load Tests Gatling
        // ─────────────────────────────────────────
        stage('Load Tests') {
            steps {
                sh './mvnw gatling:test -q'
                sh './mvnw gatling:test "-Dgatling.simulationClass=com.enova.notifications.load.SseConnectionSimulation" -q'
            }
            post {
                always {
                    gatlingArchive()
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // Post-pipeline
    // ─────────────────────────────────────────
    post {
        success {
            echo "✅ Pipeline SUCCESS — ${APP_NAME}:${IMAGE_TAG} — branch: ${env.BRANCH}"
        }
        failure {
            echo "❌ Pipeline FAILURE — stage: ${env.STAGE_NAME}"
        }
        always {
            sh "docker rmi ${IMAGE_NAME}:${IMAGE_TAG} || true"
            cleanWs()
        }
    }
}
