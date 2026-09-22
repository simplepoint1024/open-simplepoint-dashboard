dependencies {
    implementation(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-api"))
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework:spring-tx")
    implementation(libs.swagger.annotations)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))
}

tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
