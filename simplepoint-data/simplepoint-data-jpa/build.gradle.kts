dependencies {
    implementation(project(":simplepoint-core"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testRuntimeOnly("com.h2database:h2")
}
