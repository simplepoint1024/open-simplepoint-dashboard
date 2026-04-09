dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
}
