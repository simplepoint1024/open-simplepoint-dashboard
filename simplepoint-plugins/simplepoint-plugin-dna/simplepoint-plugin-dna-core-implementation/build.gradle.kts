dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(libs.swagger.annotations)
}
