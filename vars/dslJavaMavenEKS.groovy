pipeline {
    agent {
        kubernetes {
            label "k8s-agent-mvn"
            yamlFile "KubernetesPod.yaml" // contains pod template for jenkins agent
            defaultContainer "jnlp" // container name on which pipeline script gets executed
        }
    }
    options {
        timestamps()
        disableconcurrentBuilds()
        timeout(
            time: 60,
            unit: "MINUTES")
        buildDiscarder(logRotator(
            numToKeepStr: configuration.logRotatorNumToKeep: "30",
            artifactNumTokeepStr: configuration.logRotatorArtifactNumToKeepStr: "3"))
    }
    environment {

    }

    stages {
        stage("Initialization") {
            steps {
                script {

                }
            }
        }
        stage("Build & Test ") {
            steps {
                
            }
        }

    }
}