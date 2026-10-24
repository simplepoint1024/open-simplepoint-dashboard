dependencies {
    api(project(":simplepoint-events:simplepoint-events-rabbit"))
    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation(project(":simplepoint-events:simplepoint-events-migrations"))
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}
