package com.devopsworks.inc.git

public class GitUtil {

    public static String getNameFromRepository(String repository) {

        return (repository =~ /.*\/(.+).git/)[0][1]
    }
    
    public static void cloneRepositories(
        Script scriptObj,
        List repositories,
        String credentialsId,
        List branches) {
            for (repo in repositories){
                this.cloneRepository(scriptObj, repo, credentialsId, branches)
            }
    }

    public static void cloneRepository(
        Script scriptObj,
        String repository,
        String credentialsId,
        List branches) {
            def repoName = this.getNameFromRepository(repository)
            scriptObj.checkout([
                $class: "GitSCM",
                branches: branches,
                extensions:[
                    [$class: "WipeWorkspace"],
                    [$class: "RelativeTargetDirectory", relativeTargetDir: repoName],
                    [$class: "CloneOption", noTags: false, reference:"", shallow: true]
                ],
                userRemoteConfigs:[
                    ["CrendentialsId": credentialsId, url: repository]]
            ])
    }
}