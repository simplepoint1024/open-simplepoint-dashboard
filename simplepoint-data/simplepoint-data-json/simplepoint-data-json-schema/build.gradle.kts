dependencies{
    implementation("org.springdoc:springdoc-openapi-starter-common")
    api("com.github.victools:jsonschema-generator")
    api("com.github.victools:jsonschema-module-jackson")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("com.github.victools:jsonschema-module-jakarta-validation")
    api(project(":simplepoint-api"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-core"))
}

tasks.test {
    inputs.file(rootProject.file("simplepoint-react/scripts/form-validation-contract.fixture.json"))
}
