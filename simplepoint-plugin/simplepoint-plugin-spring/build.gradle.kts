group = "org.simplepoint.plugin"


dependencies {
    implementation("org.springframework:spring-beans")
    implementation("org.springframework:spring-context")
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-plugin:simplepoint-plugin-core"))

}