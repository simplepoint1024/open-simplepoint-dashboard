dependencies {
    api(project(":simplepoint-events:simplepoint-events-api"))
    api("org.springframework:spring-jdbc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
}
