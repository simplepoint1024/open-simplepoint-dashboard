dependencies {
    implementation(project(":simplepoint-core"))
    implementation("com.github.victools:jsonschema-module-jakarta-validation")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.swagger.annotations)
    api("org.springframework.boot:spring-boot-starter-security")
}