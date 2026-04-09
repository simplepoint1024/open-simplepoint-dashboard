dependencies {
    api(project(":simplepoint-core"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.swagger.annotations)
}
