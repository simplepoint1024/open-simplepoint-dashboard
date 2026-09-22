allprojects {
    group = "org.simplepoint.core"
}
subprojects {
    apply(plugin = "java-library")
}

plugins {
    antlr
    jacoco
}

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    api("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.slf4j:slf4j-api")
    api(project(":simplepoint-api"))
    api("org.springframework.data:spring-data-commons")
    api("org.springframework:spring-core")
    api("org.hibernate.orm:hibernate-core")
    api("org.springframework.security:spring-security-oauth2-core")
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("tools.jackson.core:jackson-databind")
    api(libs.swagger.annotations)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-jackson")
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

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-no-listener")
}
