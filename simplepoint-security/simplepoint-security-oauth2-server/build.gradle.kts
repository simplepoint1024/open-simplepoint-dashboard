dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    api(libs.oauth2.oidc.sdk)
    api(libs.spring.authorization.server)
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-service"))
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.2")
    implementation("org.apache.tomcat.embed:tomcat-embed-core")
    implementation("commons-codec:commons-codec:1.16.0")

    // Spring Vault（核心）
    api("org.springframework.vault:spring-vault-core")
    // Spring Cloud Vault（自动配置）
    api("org.springframework.cloud:spring-cloud-starter-vault-config")
    // K8s
    //implementation("org.springframework.cloud:spring-cloud-starter-vault-config-kubernetes")
}