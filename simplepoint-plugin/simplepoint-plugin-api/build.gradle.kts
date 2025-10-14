group = "org.simplepoint.plugin"

dependencies {
    implementation(project(":simplepoint-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.swagger.annotations)
    implementation(libs.oauth2.oidc.sdk)
}