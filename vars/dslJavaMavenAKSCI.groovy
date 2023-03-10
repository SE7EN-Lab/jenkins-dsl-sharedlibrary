import com.devopsworks.inc.jenkins.JenkinsUtil
import com.devopsworks.inc.workflow.driver.*
import com.devopsworks.inc.fortify.FortifyResult
import com.devopsworks.inc.parameters.ParametersReader
import com.devopsworks.inc.snyk.SnykResult
import com.devopsworks.inc.sonarqube.SonarQubeResult
import com.devopworks.inc.util.QualityCheck
import com.devopsworks.inc.gitflow.GitFlowUtil
import com.devopsworks.inc.util.notifications.NotificationsPropertiesCatalogBuilder
import com.devopsworks.inc.util.propertyFile.PropertiesCatalog
import com.devopsworks.inc.util.propertyFile.PropertyFilesReader
import com.devopsworks.inc.util.validator.EnvironmentParams
import com.devopsworks.inc.snyk.SnykRunner
import com.devopsworks.inc.util.reports.PipelineReport

public Map configuration
public Properties pipelineParams
private ParametersReader ParametersReader
public SonarQubeResult sonarQubeResult
public FortifyResult FortifyResult
public SnykResult SnykResult
private PipelineReport PipelineReport
private Boolean statusCodeScan

def call(Map configuration) {
    this.configuration = configuration
    defaultPath = JenkinsUtil.getNodeVersionPath(configuration) + ":" + JenkinsUtil.getDefaultPath()
    def javaHomePath = JenkinsUtil.getJavaHomePath(configuration)

    pipeline {
        agent {
            docker {
                image JenkinsUtil.getAgentDockerImage()
                args "-u devops:docker --previleged -e PATH=${defaultPath} -v /app/maven/.m2:/home/devops/.m2 -v /var/run/docker.sock:/var/run/docker.sock"
            }
        }
        options {
            timestamps()
            disableConcurrentBuilds()
            timeout(
                time: configuration.timeoutTime ? configuration.timeoutTime : 6,
                unit: configuration.timeoutUnit ? configuration.timeoutUnit : HOURS)
            buildDiscarder(logRotator(
                numToKeepStr: configuration.logRotatorNumToKeep ? configuration.logRotatorNumToKeep : "30",
                artifactNumTokeepStr: configuration.logRotatorArtifactNumToKeepStr ? configuration.logRotatorArtifactNumToKeepStr : "3"))
        }

        environment {
            pipelineName = "ci"
            framework = "java"
            JAVA_HOME = "/etc/alternatives/jre"
            ARTIFACTID = readMavenPom().getArtifactId()
            VERSION = readMavenPom().getVersion()
            GROUPID = readMavenPom().getGroupId()
            ARTIFACT_PACKAGING = readMavenPom().getPackaging()
            DOCKER_CONTENT_TRUST = "1"
            DOCKER_CONTENT_TRUST_SERVER = "https://devopsworks.com"
            DOCKER_CONFIG = "${WORKSPACE}/.docker"
            statusCodeScan = false
        }
        stages {
            stage("Initialization") {
                steps {
                    script {
                        env.stageTail = ""
                        env.standardConvention = ""
                        sonarQubeResult = new SonarQubeResult()
                        fortifyResult = new FortifyResult()
                        snykResult = new SnykResult()
                        pipelineReport = new PipelineReport()
                        pipelineParams = new Properties()
                        paramsReader = new ParametersReader(this)
                        this.configuration.sourceLanguage = "java"

                        def environmentParams = new EnvironmentParams(this)
                        env.jenkinsProjectName = JOB_URL.tokenize('/')[4]

                        this.configuration.customSharedLibrary = 'devops'
                        this.configuration['deployToAks'] = true
                        if(this.configuration.customSharedLibrary) {
                            echo "customLibrary is ${this.configuration.customSharedLibrary}"
                        }
                        echo "customeLibrary: ${this.configuration.customSharedLibrary}"

                        paramsDriverObj = new PipelineParamsDriver(this, 'PipelineParamsReaderAks')
                        paramsDriverObj.main()
                    }
                }
            }
            stage("Unit Test") {
                when {
                    expression {return !paramsReader.readPipelineParams('skipUnitTest')}
                }
                steps {
                    script {
                        unitTestDriverObj = new UnitTestDriver(this)
                        unitTestDriverObj.main()

                    }
                }
            }
            stage ("Code Scan") {
                parallel {
                    stage ("Code Quality Scan") {
                        when {
                            expression { return !paramsReader.readPipelineParams('skipCodeQualityScan') }
                        }
                        stages {
                            stage("code Scan Operations"){
                                steps {
                                    script {
                                        codeScanDriverObj = new CodeScanDriver(this, 'CodeScanner')
                                        codeScanDriverObj.main()
                                    }
                                }
                            }
                        }
                    }
                    stage("Fortify Scan") {
                        when {
                            expression { return !paramsReader.readPipelineParams('skipFortifyScan')}
                        }
                        stages {
                            stage("Fortify Scan Operations") {
                                steps {
                                    script {
                                        fortifyScanDriverObj = new FortifyScanDriver(this)
                                        fortifyScanDriverObj.main()
                                    }
                                }
                            }
                        }
                    }
                    stage("Open Source Governance") {
                        when {
                            expression { return !paramsReader.readPipelineParams('skipSnykScan')}
                        }
                        stages {
                            stage("Snyk Scan Operations") {
                                steps {
                                    script {
                                        snykScanDriverObj = new SnykScanDriver(this)
                                        snykScanDriverObj.main()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage("Upload to Artifactory") {
                when {
                    allOf {
                        expression { return !paramsReader.readPipelineParams('skipUploadArtifactory')}
                        expression { return JenkinsUtil.getBranchesFilter(this)}
                        anyOf { branch 'master'; branch 'develop/*'; branch 'hotfix/*'; branch 'release/*'}
                    }
                }
                steps {
                    scripts {
                        artifactoryUploadDriverObj = new ArtifactoryUploadDriver(this)
                        artifactoryUploadDriverObj.main()
                    }
                }
            }
            stage("Deploy to AKS") {
                when {
                    allOf {
                        expression { return paramsReader.readPipelineParams('deployToAks')}
                        expression { return !paramsReader.readPipelineParams('skipDeploy') && !paramsReader.readPipelineParams('skipDeploy') != null }
                        anyOf { branch 'master'; branch 'develop/*'; branch 'hotfix/*'; branch 'release/*'}
                    }
                }
                stages {
                    stage("Deploy to AKS by custom driver") {
                        steps {
                            script {
                                aksPushObj = new AksPushDriver(this)
                                aksPushObj.main()
                            }
                        }
                    }
                }
            }
            stage("NewRelic Performance Analysis") {
                when {
                    allOf {
                        expression { return !paramsReader.readPipelineParams('enableNewRelicAnalysis')}
                        expression { return QualityGateCheck.isCodeScanPassed(this)}
                    }
                }
                stages {
                    stage("NewRelic main Operation") {
                        steps {
                            script {
                                newRelicDriverObj = new NewRelicDriver(this)
                                newRelicDriverObj.main()
                            }
                        }
                    }
                }
            }
            stage("Deployment marker") {
                when {
                    allOf {
                        expression { return paramsReader.readPipelineParams('enableMarkerDeployment')}
                        expression { return QualityGateCheck.isCodeScanPassed(this)}
                    }
                }
                stages {
                    stage("Deployment Marker main Operation") {
                        steps {
                            script {
                                markerDeploymentDriverObj = new MarkerDeploymentDriver(this)
                                markerDeploymentDriverObj.main()
                            }
                        }

                    }
                }
            }
            stage("Smoke Test") {
                when {
                    allOf {
                        expression { return !parametersReader.readPipelineParams('skipSmokeTest') && !paramsReader.readPipelineParams('isLibrary')}
                        expression { return QualityGateCheck.isCodeScanPassed(this)}
                    }
                }
                environment {
                    SMOKE_TARGET_URL = "${paramsReader.readPipelineParams('smokeTestUrl)}"
                }
                steps {
                    script {
                        smokeTestDriverObj = new SmokeTestDriver(this, 'SmokeTestAks')
                        smokeTestDriverObj.main()
                    }
                }
            }
            stage("Update and Release Branches") {
                when {
                    allOf {
                        expression { return !paramsReader.readPipelineParams('skipUpdateBranches')}
                        expression { return QualityGateCheck.isCodeScanPassed(this)}
                    }
                }
                steps {
                    script {
                        gitFlowUpdateDriverObj = new GitFlowUpdateDriver(this)
                        gitFlowUpdateDriverObj.main()
                    }
                }
            }
            stage("Trigger CD") {
                when {
                    allOf {
                        expression { return paramsReader.readPipelineParams('triggerNextPipeline') && QualityGateCheck.isCodeScanPassed(this)}
                    }
                }
                steps {
                    script {
                        triggerCDDriverObj = new TriggerCDDriver(this)
                        triggerCDDriverObj.main()
                    }
                }
            }
            post {
                always {
                    script {
                        postProcessDriverObj = new postProcessDriver(this)
                        postProcessDriverObj.main()
                    }
                }
            }
        }
    }
}