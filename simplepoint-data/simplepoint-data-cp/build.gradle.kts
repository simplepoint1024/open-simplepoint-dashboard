dependencies {
    testImplementation(platform("org.junit:junit-bom"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(project(":simplepoint-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("org.postgresql:postgresql:42.7.5")

}