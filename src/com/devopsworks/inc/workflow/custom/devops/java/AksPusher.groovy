package com.devopsworks.inc.workflow.custom.devops.java
import com.devopsworks.inc.workflow.interfaces.AksPushInterface
import com.devopsworks.inc.logger.*
import com.devopsworks.inc.parameters.*
import com.devopsworks.inc.git.GitUtil

class AksPusher implements AksPushInterface {
    protected Script scriptObj
    protected  paramsReader paramsReader
    protected Logger logger
    private String lowerArtifactId
    private String lowerVersion
    private String DOCKER_REGISTRY_URL = "artifactory.inc.devopsworks.com"
    private String DOCKER_REGISTRY_CONTEXT = "/docker"
    private String ACR_LOGIN_URL = DOCKER_REGISTRY_URL + DOCKER_REGISTRY_CONTEXT
    private String helmTemplRepo = "ssh://git.inc.devopsworks.com:8080/helm/templates.git"
    private List helmTemplBranches = [[name: "master"]]
    private String pipelinePropertiesFolder
    private String chartVersion = "1.0.0"
    private String k8sPath
    private String helmChartPath
    private String helmChartRepoPath
    private String helmNetworkChartPath
    private String helmChartTemplPath
    private Boolean helmRollBack

    AksPusher(Script scriptObj) {
        this.scriptObj = scriptObj
        this.logger = new Logger(scriptObj, Level.INFO)
        this.paramsReader = new ParametersReader(scriptObj)
        this.lowerArtifactId = scriptObj.env.ARTIFACTID.toLowerCase()
        this.lowerVersion = scriptObj.env.VERSION.toLowerCase()
    }

    public void aksPushPreparations() {
        logger.info('==== Auth with Azure ====')
        def credentialsId = paramsReader.readPipelineParams('AZURE_SERVICE_PRINCIPAL_SECRET_ID') ? paramsReader.readPipelineParams('AZURE_SERVICE_PRINCIPAL_SECRET_ID') : 'AZURE_SERVICE_PRINCIPAL_SECRET_NONPROD'
        def clientId = paramsReader.readPipelineParams('AZURE_SERVICE_PRINCIPAL_ID') ? paramsReader.readPipelineParams('AZURE_SERVICE_PRINCIPAL_ID') : paramsReader.readPipelineParams('AZURE_SERVICE_PRINCIPAL_ID')
        scriptObj.withCredentials([
            scriptObj.string(credentialsId: "${credentialsId}", variable: 'AZURE_SERVICE_PRINCIPAL_SECRET')
        ]){
            scriptObj.sh "az login --service-principal -u ${clientId} -p ${scriptObj.env.AZURE_SERVICE_PRINCIPAL_SECRET} -t ${paramsReader.readPipelineParams('AZURE_TENANT_ID')}"

        }
        !paramsReader.readPipelineParams('HELM_TEMPLATE_REPO') ?: (this.helmTemplRepo = paramsReader.readPipelineParams('HELM_TEMPLATE_REPO'))
        !paramsReader.readPipelineParams('HELM_TEMPLATE_BRANCH') ?: (this.helmTemplBranches = [[name: paramsReader.readPipelineParams('HELM_TEMPLATE_BRANCH')]])
        !paramsReader.readPipelineParams('pipelinePropertiesDir') ?: (this.pipelineFolder = paramsReader.readPipelineParams('pipelinePropertiesDir'))
        !paramsReader.readPipelineParams('pipelinePropertiesFolder') ?: (this.pipelineFolder = paramsReader.readPipelineParams('pipelinePropertiesFolder'))
        !paramsReader.readPipelineParams('HELM_CHART_VERSION') ?: (this.chartVersion = paramsReader.readPipelineParams('HELM_CHART_VERSION'))
        !paramsReader.readPipelineParams('k8S_PATH') ?: (this.k8sPath = paramsReader.readPipelineParams('K8S_PATH'))
        !paramsReader.readPipelineParams('HELM_CHART_PATH') ?: (this.helmChartPath = paramsReader.readPipelineParams('HELM_CHART_PATH'))
        !paramsReader.readPipelineParams('HELM_ROLLBACK') ?: (this.helmRollBack = true) : (this.helmRollBack = Boolean.parseBoolean(paramsReader.readPipelineParams('HELM_ROLLBACK')))

        this.helmChartRepoPath = "${pipelinePropertiesFolder}/${chartVersion}/${k8sPath}/${helmChartPath}"
        this.helmNetworkChartPath = "${pipelinePropertiesFolder}/${chartVersion}/${k8sPath}/${helmChartPath}/network"
        this.helmChartTemplPath = "${pipelinePropertiesFolder}/${chartVersion}/${k8sPath}/${helmChartPath}/release"

        GitUtil.cloneRepository(
            scriptObj,
            helmTemplRepo,
            scriptObj.scm.getUserRemoteConfigs()[0].getCredentialsId(),
            helmTemplBranches,
            helmChartRepoPath
        )
        if (scriptObj.env.pipelineName == 'ci') {
            GitUtil.cloneRepository(
                scriptObj,
                "ssh://git.inc.devopsworks.com:8080/devopsutils/notary-trust-store.git",
                scriptObj.scm.getUserRemoteConfigs()[0].getCredentialsId(),
                [[name:"master"]],
                ".docker"
            )
            String JAR_FILE = "${scriptObj.env.ARTIFACTID}-${scriptObj.env.VERSION}.jar"
            scriptObj.sh """
            mkdir -p ${pipelinePropertiesFolder}/docker-build/target && \
            cp target/${JAR_FILE} ${pipelinePropertiesFolder}/docker-build/target/ && \
            cp ${helmChartRepoPath}/docker/Dockerfile ${pipelinePropertiesFolder}/docker-build/ && \
            docker build --build-arg JAR_FILE=${JAR_FILE} . -t ${ACR_LOGIN_URL}"/"${lowerArtifactId}:${lowerVersion}
            """
            logger.info("====Push built image to Artifactory====")
            scriptObj.withCredentials([
                scriptObj.string(credentialsId: 'DCT_ROOT_SECRET', variable: 'DOCKER_CONTENT_TRUST_ROOT_PASSPHRASE'),
                scriptObj.string(credentialsId: 'DCT_REPO_SECRET', variable: 'DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE'),
                scriptObj.usernamePassword(credentialsId: 'DOCKER_SECRET', usernameVariable: 'DOCKER_NAME', passwordVariable: 'DOCKER_PASSWORD')
            ]){
                scriptObj.sh "docker login ${DOCKER_REGISTRY_URL} -u ${scriptObj.env.DOCKER_NAME} -p ${scriptObj.env.DOCKER_PASSWORD}"
                scriptObj.sh "docker push ${ACR_LOGIN_URL}/${lowerArtifactId}:${lowerVersion}"
            }
            scriptObj.dir(".docker"){
                String divertCounter = scriptObj.sh(script: "git status -s | wc -l", returnStdout: true, label: "check key changes")
                def isChanged = divertCounter.toInteger()
                if (isChanged > 0){
                    logger.info("Commit DCT target key for new app ${scriptObj.env.ARTIFACTID}")
                    scriptObj.sh """
                    git config --global user.name DevOps && \ 
                    git config --global user.email devops_admin@devopsworks.com && \
                    git add --all && \
                    git commit --all -m "Commit new target key for app:[${scriptObj.env.ARTIFACTID}]" && \
                    git push origin HEAD:master
                    """
                } else {
                    logger.info("No DCT key need to commit")
                }
            }
            logger.info('snyk scanning the dockerfile')
            try {
                def snykId = paramsReader.readPipelineParams('snykTokenId')?paramsReader.readPipelineParams('snykTokenId'):'SNYK_HK_ORG'
                scriptObj.withCredentials([scriptObj.string(credentialsId: snykId, variable: 'SNYK_TOKEN')]) {
                    scriptObj.sh "snyk auth -d ${scriptObj.env.SNYK_TOKEN}"
                }
                scriptObj.configuration['snykDockerUrl'] = "${ACR_LOGIN_URL}/${lowerArtifactId}: ${lowerVersion}"
                scriptObj.configuration['snykFile'] = "Dockerfile"
                scriptObj.dir("${pipelinePropertiesFolder}/docker-build"){
                    def snykRunnerObj = new com.devopsworks.snyk.SnykRunner(scriptObj)
                    snykRunnerObj.run()
                }
                catch(Exception e) {
                    logger.info("${e}\n=== Error in Snyk, will continue===")
                }
            }
            logger.info("===Helm Packaging and Upload to Artifactory===")
            scriptObj.withCredentials([scriptObj.usernamePassword(credentialsId: 'DOCKER_SECRET', usernameVariable: 'DOCKER_NAME', passwordVariable: 'DOCKER_PASSWORD')]){
                scriptObj.sh "az eks get-credentials --resource-group ${paramsReader.readPipelineParams('AZURE_RESOURCE_GROUP')} --name ${paramsReader.readPipelineParams('AZURE_AKS_CLUSTER_NAME')} --admin --overwrite-existing"
                scriptObj.sh """
                    helm repo add helm https://artifactory.inc.devopsworks.com/artifactory/helm --username ${scriptObj.env.DOCKER_NAME} --password ${scriptObj.env.DOCKER_PASSWORD}
                    helm repo update
                """
                scriptObj.sh(script: "kubectl get svc/svc-${lowerArtifactId} -n ${paramsReader.readPipelineParams('K8S_TARGET_NAMESPACE')}", returnStatus: true)
                scriptObj.sh """
                    sed -i 's/appVersion: .*/appVersion: network/g' ${helmNetworkChartPath}/Chart.yaml && \
                    sed -i 's/name: .*/name: ${lowerArtifactId}-network/g' ${helmNetworkChartPath}/Chart.yaml && \
                    helm package ${helmNetworkChartPath}
                """
                scriptObj.sh "curl -u${scriptObj.env.DOCKER_NAME}:${scriptObj.env.DOCKER_PASSWORD} -T ${lowerArtifactId}-network-${chartVersion}.tgz 'https://artifactory.inc.devopsworks.com/artifactory/helm/'"
                scriptObj.sh """
                    sed -i 's/appVersion: .*/appVersion: ${lowerVersion}/g' ${helmChartTemplPath}/Chart.yaml && \
                    sed -i 's/name: .*/name: ${lowerArtifactId}-${lowerVersion}/g' ${helmChartTemplPath}/Chart.yaml && \
                    helm package ${helmChartTemplPath}
                """
                scriptObj.sh "curl -u${scriptObj.env.DOCKER_NAME}:${scriptObj.env.DOCKER_PASSWORD} -T ${lowerArtifactId}-${lowerVersion}-${chartVersion}.tgz 'https://artifactory.inc.devopsworks.com/artifactory/helm'"
                scriptObj.sh "helm repo update"
            }
        }
    }
    @NonCPS
    public String getRegionId() {
        try {
            def regionId = scriptObj.pipelineParams.AZURE_RESOURCE_GROUP.toLowerCase().trim()
            def matcher = ( regionId =~ /^([A-Za-z]+)-/ )
            return matcher[0][1].toString().trim()
        } catch (Exception err) {
            return null
        }
    }
    public void aksPushMainOperations() {
        logger.info("====Deploy to AKS ====")
        def valueYamlSuffix, valuesFile
        if ( getRegionId() || getRegion() != '') {
            valueYamlSuffix = getRegionId()
            valuesFile = pipelinePropertiesFolder + "/" + k8sPath + "/values/values-${scriptObj.env.targetEnvironment}-${valueYamlSuffix}.yaml"
        } else {
            valuesFile = pipelinePropertiesFolder + "/" + k8sPath + "/values/values-${scriptObj.env.targetEnvironment}.yaml"
        }
        if (!scriptObj.fileExists("${valuesFile}")) {
            valuesFile = pipelinePropertiesFolder + "/" + k8sPath + "/values/values-${scriptObj.env.targetEnvironment}.yaml"
        }
        def build_commit = ''

        if ( scriptObj.env.pipelineName != 'ci') {
            scriptObj.withCredentials([scriptObj.UsernamePassword(credentialsId: 'DOCKER_SECRET', usernameVariable: 'DOCKER_NAME', passwordVariable: 'DOCKER_PASSWORD')]){
                scriptObj.sh "az aks get-credentials --resource-group ${paramsReader.readPipelineParams('AZURE_RESOURCE_GROUP')} --name ${paramsReader.readPipelineParams('AZURE_AKS_CLUSTER_NAME')} --admin --overwrite-existing"
                scriptObj.sh """
                    helm repo add helm https://artifactory.inc.devopsworks.comf/artifactory/helm --username ${scriptObj.env.DOCKER_NAME} --password ${scriptObj.env.DOCKER_PASSWORD}
                    helm repo update
                """
            }
            build_commit = scriptObj.sh(script: "unzip -p target/*.jar \$(unzip -l target/*.jar | grep git | awk '{print \$4}') | grep 'git.commit.id.abbrev' | awk -F= '{print \$2}'", returnStdout: true).trim()
        } else {
            build_commit = scriptObj.sh(script: "grep 'git.commit.id.abbrev' target/classes/git.properties | awk -F= '{print \$2}'", returnStdout: true).trim()
        }
        def dnsAppName = lowerArtifactId.replaceAll("\\.", "-") + "-" + lowerVersion.replaceAll("\\.", "-")
        scriptObj.sh """
            echo "runningInstances:" > runningInstances.yaml && \
            kubectl get pod -n ${paramsReader.readPipelineParams('K8S_TARGET_NAMESPACE')} --field-selector=status.phase=Running -l app.kubernetes.io/appName=${lowerArtifactId} -o json | jq '.items[].metadata.labels | ."apps.kubernetes.io/instance"' -r  \
            | while IFS= read -r line; do printf " - \$line\\n"; done >> runningInstances.yaml
        """
        scriptObj.sh """
            helm upgrade -i ${rollBack} --cleanup-on-fail ${lowerArtifactId}-network-${chartVersion} helm/${lowerArtifactId}-network \
            --version ${chartVersion} \
            --set image.repository=${ACR_LOGIN_URL} \
            --set appName=${lowerArtifactId} \
            --set appVersion=${lowerVersion} \
            --set gitCommit=${build_commit} \
            --namespace ${paramsReader.readPipelineParams('K8S_TARGET_NAMESPACE')} \
            --values ${valuesFile} \
            --values runningInstances.yaml
        """
        // } 
    }
    public void aksPushPostOperations() {
        logger.info('empty aksPushPostOperations body, please inject your custom code here')
    }

}