dependencies {
    implementation(project(":simplepoint-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.apache.calcite:calcite-core:1.40.0")
    implementation(project(":simplepoint-data:simplepoint-data-calcite:simplepoint-data-calcite-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-repository"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}
