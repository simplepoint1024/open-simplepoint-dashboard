dependencies {
    implementation(project(":simplepoint-platform:simplepoint-platform-resource-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-data:simplepoint-data-calcite:simplepoint-data-calcite-core"))
    implementation("org.apache.calcite:calcite-core:${rootProject.libs.versions.calcite.get()}")
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-tx")
    implementation("io.micrometer:micrometer-core")

    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:${rootProject.libs.versions.spring.boot.get()}"
    )

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")
}


tasks.test {
    systemProperty("simplepoint.dna.migrations", rootProject.file("doc/deployment/sql").absolutePath)
}
