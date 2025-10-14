dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    api(project(":simplepoint-core"))
    implementation("org.apache.calcite:calcite-core:1.40.0")
    implementation("mysql:mysql-connector-java:8.0.33")
}