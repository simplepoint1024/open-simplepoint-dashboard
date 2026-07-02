group = "org.simplepoint.plugin"


dependencies {
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework:spring-beans")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-plugin:simplepoint-plugin-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")

}
