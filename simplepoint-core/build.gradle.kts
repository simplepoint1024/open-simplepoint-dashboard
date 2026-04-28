allprojects {
    group = "org.simplepoint.core"
}
subprojects {
    apply(plugin = "java-library")
}

plugins {
    jacoco
}

dependencies {
    implementation("org.slf4j:slf4j-api")
    api(project(":simplepoint-api"))
    api("org.springframework.data:spring-data-commons")
    api("org.springframework:spring-core")
    api("org.hibernate.orm:hibernate-core")
    api("org.springframework.security:spring-security-oauth2-core")
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-annotation"))
    api(libs.swagger.annotations)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(false)
        html.required.set(true)
    }
}