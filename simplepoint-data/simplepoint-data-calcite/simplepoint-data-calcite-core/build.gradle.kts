dependencies {
    implementation("org.apache.calcite:calcite-core:${rootProject.libs.versions.calcite.get()}")
    implementation("mysql:mysql-connector-java:8.0.33")

    testImplementation("com.h2database:h2")
}
