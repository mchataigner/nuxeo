/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
library identifier: "platform-ci-shared-library@v0.0.25"

void getCurrentVersion() {
  return readMavenPom().getVersion()
}

void promoteDockerImage(String dockerRegistry, String imageName, String buildVersion, String releaseVersion, String latestVersion) {
  String buildImage = "${dockerRegistry}/${DOCKER_NAMESPACE}/${imageName}:${buildVersion}"
  String releaseImage = "${dockerRegistry}/${DOCKER_NAMESPACE}/${imageName}:${releaseVersion}"
  String latestImage = "${dockerRegistry}/${DOCKER_NAMESPACE}/${imageName}:${latestVersion}"

  // the source image is multi-platform, so the manifest is a list of images
  // copy all of the images in the list and the list itself
  nxDocker.copy(from: buildImage, tos: [releaseImage, latestImage], options: '--all')
}

pipeline {

  agent {
    label 'jenkins-nuxeo-package-lts-2023'
  }

  environment {
    NUXEO_BRANCH = "${params.NUXEO_BRANCH}"
    NUXEO_BUILD_VERSION = "${params.NUXEO_BUILD_VERSION}"
    CURRENT_VERSION = getCurrentVersion()
    RELEASE_VERSION = nxUtils.getMajorDotMinorVersion(version: env.CURRENT_VERSION)
    LATEST_VERSION = nxUtils.getMajorVersion(version: env.RELEASE_VERSION)
    MAVEN_ARGS = '-B -nsu -Dnuxeo.skip.enforcer=true -P-nexus,nexus-private'
    DOCKER_NAMESPACE = 'nuxeo'
    NUXEO_IMAGE_NAME = 'nuxeo'
  }

  stages {
    stage('Set Kubernetes labels') {
      steps {
        container('maven') {
          script {
            nxK8s.setPodLabels()
          }
        }
      }
    }

    stage('Info') {
      steps {
        echo """
        ----------------------------------------
        Build version:   ${NUXEO_BUILD_VERSION}
        Current version: ${CURRENT_VERSION}
        Release version: ${RELEASE_VERSION}
        ----------------------------------------
        """
      }
    }

    stage('Release') {
      steps {
        container('maven') {
          script {
            echo """
            -------------------------------------------------
            Release nuxeo-parent POM ${RELEASE_VERSION} from build ${NUXEO_BUILD_VERSION}
            -------------------------------------------------
            """
            sh """
              git checkout v${NUXEO_BUILD_VERSION}

              mvn ${MAVEN_ARGS} -f parent/pom.xml versions:set -DnewVersion=${RELEASE_VERSION} -DgenerateBackupPoms=false
              mvn ${MAVEN_ARGS} -f parent/pom.xml validate
            """
            nxGit.commitTagPush(version: env.RELEASE_VERSION)
          }
        }
      }
    }

    stage('Deploy nuxeo-parent POM') {
      when {
        expression { !nxUtils.isDryRun() }
      }
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Deploy nuxeo-parent POM
          ----------------------------------------"""
          sh "mvn ${MAVEN_ARGS} -f parent/pom.xml deploy"
        }
      }
    }

    stage('Upload Nuxeo Packages') {
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Upload Nuxeo Packages to ${CONNECT_PROD_SITE_URL}
          ----------------------------------------"""
          script {
            sh """
              # Fetch Nuxeo packages with Maven
              mvn ${MAVEN_ARGS} -f ci/release/pom.xml process-resources
            """
            def nxPackages = findFiles(glob: 'ci/release/target/packages/nuxeo-*-package-*.zip')
            for (nxPackage in nxPackages) {
              nxUtils.postForm(credentialsId: 'connect-prod', url: "${CONNECT_PROD_SITE_URL}marketplace/upload?batch=true",
                  form: ["package=@${nxPackage.path}"])
            }
          }
        }
      }
    }

    stage('Promote Docker image') {
      when {
        expression { !nxUtils.isDryRun() }
      }
      steps {
        container('maven') {
          echo """
          -----------------------------------------------
          Tag Docker images with version ${RELEASE_VERSION} and ${LATEST_VERSION}
          -----------------------------------------------
          """
          promoteDockerImage("${PRIVATE_DOCKER_REGISTRY}", "${NUXEO_IMAGE_NAME}", "${NUXEO_BUILD_VERSION}",
            "${RELEASE_VERSION}", "${LATEST_VERSION}")
        }
      }
    }

    stage('Bump reference branch') {
      steps {
        container('maven') {
          script {
            sh 'git checkout ${NUXEO_BRANCH}'
            // increment minor version
            def nextVersion = sh(returnStdout: true, script: "perl -pe 's/\\b(\\d+)(?=\\D*\$)/\$1+1/e' <<< ${CURRENT_VERSION}").trim()
            echo """
            -----------------------------------------------
            Update ${NUXEO_BRANCH} version from ${CURRENT_VERSION} to ${nextVersion}
            -----------------------------------------------
            """
            sh """
              # root POM
              mvn ${MAVEN_ARGS} -Pdistrib,docker versions:set -DnewVersion=${nextVersion} -DgenerateBackupPoms=false
              perl -i -pe 's|<nuxeo.platform.version>.*?</nuxeo.platform.version>|<nuxeo.platform.version>${nextVersion}</nuxeo.platform.version>|' pom.xml
              perl -i -pe 's|org.nuxeo.ecm.product.version=.*|org.nuxeo.ecm.product.version=${nextVersion}|' server/nuxeo-nxr-server/src/main/resources/templates/nuxeo.defaults

              # nuxeo-parent POM
              perl -i -pe 's|<version>.*?</version>|<version>${nextVersion}</version>|' parent/pom.xml

              # nuxeo-promote-packages POM
              perl -i -pe 's|<version>.*?</version>|<version>${nextVersion}</version>|' ci/release/pom.xml
            """
            nxGit.commitPush(message: "Release ${RELEASE_VERSION}, update ${CURRENT_VERSION} to ${nextVersion}", branch: env.NUXEO_BRANCH)
          }
        }
      }
    }
  }

  post {
    always {
      script {
        currentBuild.description = "Release ${RELEASE_VERSION} from build ${NUXEO_BUILD_VERSION}"
      }
    }
    unsuccessful {
      script {
        nxSlack.error(message: "Failed to release nuxeo/nuxeo-lts ${RELEASE_VERSION} from build ${NUXEO_BUILD_VERSION}: ${BUILD_URL}")
      }
    }
  }
}
