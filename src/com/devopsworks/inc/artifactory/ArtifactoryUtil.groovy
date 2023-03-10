package com.devopsworks.inc.artifactory

public class ArtifactoryUtil {
    def isUnix

    public static String deployFile(Script scriptObj, String file) {
        
        def pom = scriptObj.readMavenPom file: "pom.xml"
        def repoURL = pom.getVersion().endsWith("-SNAPSHOT") ? pom.getDistributionManagement().getSnapshoRepository().getUrl() : pom.getDistributionManagement().getRepository().getUrl()

        return this.deployFile(scriptObj, repoURL, pom.getGroupId(), pom.getArtifactId(), pom.getVersion(), pom.getPackaging(), file)
    }

    pubic static void deployFile(
        Script scriptObj,
        String url,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        String file) {

            def isUnix = scriptObj.isUnix()

            if(isUnix) {
                scriptObj.sh "mvn --settings settings.xml deploy:deploy-file -DgroupId=${groupId} -DartifactId=${artifactId} -Dversion=${version} -Dpackaging=${packaging} -DgeneratePom=true -Dfile=${file} -Durl=${url}"
            }
            else {
                scriptObj.bat "mvn --settings settings.xml deploy:deploy-file -DgroupId=${groupId} -DartifactId=${artifactId} -Dversion=${version} -Dpackaging=${packaging} -DgeneratePom=true -Dfile=${file} -Durl=${url}"
            }
    }

    public static String downloadArtifact(Script scriptObj, String file=null) {

        def pom = scriptObj.readMavenPom file: "pom.xml"
        def repoURL = pom.getVersion().endsWith("-SNAPSHOT") ? pom.getDistributionManagement().getSnapshoRepository().getUrl() : pom.getDistributionManagement().getRepository().getUrl()

        return this.downloadArtifact(scriptObj, repoURL, pom.getGroupId(), pom.getArtifactId(), pom.getVersion(), pom.getPackaging(),file)
    }

    public static String downloadArtifact(
        Script scriptObj,
        String url,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        String file=null) {

            def groupPath = groupId.replace(".", "/")
            def isUnix = scriptObj.isUnix()
            
            if (file == null) {
                file = "${artifactId}-${version}.${packing}"
            }

            if(isUnix) {
                scriptObj.sh "/usr/bin/curl -X GET --fail ${url}/${groupPath}/${artifactId}/${version}/${artifactId}-${version}.${packaging} -o ${file}"
            }
            else {
                scriptObj.bat "/usr/bin/curl -X GET --fail ${url}/${groupPath}/${artifactId}/${version}/${artifactId}-${version}.${packaging} -o ${file}"
            }
            
            return file

    }

}