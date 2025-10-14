allprojects {
    group = "org.simplepoint.api"
}

dependencies {
    implementation("org.slf4j:slf4j-api")
    api("org.springframework.data:spring-data-commons")
    api("org.springframework:spring-core")
    api("org.hibernate.orm:hibernate-core")
    api("org.springframework.security:spring-security-oauth2-core")
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-annotation"))
    api(libs.swagger.annotations)
}