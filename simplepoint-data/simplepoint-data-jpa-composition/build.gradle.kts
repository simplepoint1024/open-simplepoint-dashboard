dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-composition"))
    api(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-hibernate")
    implementation("org.springframework.boot:spring-boot-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-platform:simplepoint-platform-composition-spring"))
    testImplementation(project(":simplepoint-core"))
    testImplementation(project(":simplepoint-events:simplepoint-events-jdbc"))
    testRuntimeOnly("com.h2database:h2")
}
