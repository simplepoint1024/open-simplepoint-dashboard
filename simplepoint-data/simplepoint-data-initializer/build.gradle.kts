dependencies {
    implementation(project(":simplepoint-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-annotation"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}