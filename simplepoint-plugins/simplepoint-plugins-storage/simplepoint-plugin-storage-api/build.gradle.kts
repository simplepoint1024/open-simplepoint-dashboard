dependencies {
    api(project(":simplepoint-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-web")
    implementation(libs.swagger.annotations)
}
