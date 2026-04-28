dependencies {
    api(project(":simplepoint-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.swagger.annotations)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
