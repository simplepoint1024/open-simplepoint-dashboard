dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-repository"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
