Reference
---------
```
# Jenkins groovy script sample
https://gist.github.com/merikan/228cdb1893fca91f0663bab7b095757c

```
Jenkins CI/CD pipeline for dockerized java app deployed on kubernetes
---------------------------------------------------------------------
Pre-requisite:
 - Jenkins slave image should have
    - git
    - java
    - maven
    - docker
    - kubectl
    - helm
 - K8s worker nodes (DEV, STG, PRD) have
    - Docker run time
    - EFS volumes attached

1. Stage: Initialization
2. Stage: Unit Test
3. Stage: Code Quality Analysis
4. Stage: Image Build
5. Stage: Publish Image & Helm Chart
6. Stage: Deploy to k8s cluster (DEV)
7. Stage: Smoke Test application
8. Stage: Trigger Deploy to k8s cluster (STG) pipeline
9. Stage: Smoke Test application
10. Stage: Trigger Deploy to k8s cluster (PROD) pipeline
11. Stage: Smoke Test application