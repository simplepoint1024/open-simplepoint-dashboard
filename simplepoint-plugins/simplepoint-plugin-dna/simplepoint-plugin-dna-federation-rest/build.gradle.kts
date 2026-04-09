dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-api"))
    implementation(libs.swagger.annotations)
}
