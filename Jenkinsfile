#!/usr/bin/env groovy

// Not using the JOB_BASE_NAME env variable as the pr builder name would be incorrect
String full_job_name = "${env.JOB_NAME?.split('/')[1]}"

// Assume that the zip artefact has the same name as the Jenkins job
String target_file = "${full_job_name}.zip"

pipeline {
    agent { label 'docker' }

    environment {
        GIT_ID = "${sh(returnStdout: true, script: 'git describe --always').trim()}"
        BUILD_TIME = new Date().format('yyyyMMddHHmmss')
        ALIAS = "${GIT_ID}-${BUILD_TIME}"
    }

    stages {
        stage('Set build details') {
            steps {
                script {
                    currentBuild.description = "version - ${ALIAS}"
                }
            }
        }
        stage('Build artefact') {
            agent {
                dockerfile {
                    // Cache sbt dependencies
                    args "-v /tmp/.sbt:/root/.sbt -u root:root"
                }
            }
            steps {
                sh(script: "sbt assembly")
                stash(
                    name: 'artefact',
                    includes: target_file
                )
            }
        }
        stage('Run tests') {
            agent {
                dockerfile {
                    // Cache sbt dependencies
                    args "-v /tmp/.sbt:/root/.sbt -u root:root"
                }
            }
            steps {
                sh(script: "sbt test")
            }
        }
        stage('Generate sha256') {
            when {
                expression {
                    env.GIT_BRANCH == 'main'
                }
            }
            steps {
                unstash(name: 'artefact')
                sh("openssl dgst -sha256 -binary ${target_file} | openssl enc -base64 > ${full_job_name}.zip.base64sha256")
            }
        }
        stage('Upload to s3') {
            when {
                expression {
                    env.GIT_BRANCH == 'main'
                }
            }
            steps {
                sh(
                    """
                    aws s3 cp ${target_file} \
                        s3://mdtp-lambda-functions-integration/${full_job_name}/${full_job_name}_${ALIAS}.zip \
                        --acl=bucket-owner-full-control --only-show-errors
                    aws s3 cp ${full_job_name}.zip.base64sha256 \
                        s3://mdtp-lambda-functions-integration/${full_job_name}/${full_job_name}_${ALIAS}.zip.base64sha256 \
                        --content-type text/plain --acl=bucket-owner-full-control --only-show-errors
                    """
                )
            }
        }
        stage('Deploy to Integration') {
            when {
                expression {
                    env.GIT_BRANCH == 'main'
                }
            }
            steps {
                build(
                    job: 'api-platform-admin-api/deploy_lambda_version',
                    parameters: [
                        [$class: 'StringParameterValue', name: 'ARTEFACT', value: env.JOB_NAME?.split('/')[1]],
                        [$class: 'StringParameterValue', name: 'HASH', value: ALIAS],
                        [$class: 'BooleanParameterValue', name: 'ACTIVATE_INTEGRATION', value: true],
                    ]
                )
            }
        }
    }
}