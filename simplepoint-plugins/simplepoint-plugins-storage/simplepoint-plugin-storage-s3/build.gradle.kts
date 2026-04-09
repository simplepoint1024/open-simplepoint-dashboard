dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
    implementation(libs.aws.sdk.s3)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
