dependencies {
    testImplementation(platform("org.junit:junit-bom"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(project(":simplepoint-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")

}