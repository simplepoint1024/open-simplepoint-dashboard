dependencies {
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-api"))
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    implementation(libs.swagger.annotations)
}
