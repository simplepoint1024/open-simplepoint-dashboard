dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    api(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}
