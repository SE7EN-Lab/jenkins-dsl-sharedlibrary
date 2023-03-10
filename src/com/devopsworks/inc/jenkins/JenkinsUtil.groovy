package com.devopsworks.inc.jenkins

public class JenkinsUtil {
    public static String getMultibranchJobRealName(
        Script scriptObj,
        String jobName) {
            def jobNameTokens = jobName.tokenize('/') as String[]
            return jobNameTokens[-2]
    }
    public static String getDefaultPath()
    {
        String PATH="/usr/local/sbin:/usr/local/bin:" +
            "/usr/sbin:/usr/bin:/sbin:/bin:" +
            "/tech/Fortify/bin/:/tech/apache-ant-1.9.14/bin"
        return PATH
    }
    public static getNodeVersionPath(Map configuration)
    {
        String nodeVersion = "11.9.0"
        if (configuration.nodeVersion != null) {
            if ( configuration.nodeVersion.contains("12")){
                nodeVersion = "12.16.1"
            } else if (configuration.nodeVersion.contains("11")) {
                nodeVersion = "11.9.0"
            } else {
                nodeVersion = "10.15.3"
            }
        }
        return "/tech/nvm/versions/node/v"+nodeVersion+"/bin"
    }
    public static String getAgentDockerImage() {
        return "artifactory.inc.devopsworks.com/docker/docker-ci-image:2.6.1"
    }
    public static String getJavaHomePath(Map configuration) {
        String javaVersion = "1.8.0"
        if (configuration.javaVersion != null)
            javaVersion = configuration.javaVersion
        return "/usr/lib/jvm/java-" + javaVersion
    }

}