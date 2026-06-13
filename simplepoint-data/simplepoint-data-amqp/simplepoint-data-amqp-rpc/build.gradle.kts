dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    implementation("com.esotericsoftware:kryo:5.6.2")
    implementation("io.micrometer:micrometer-core")
    compileOnly("org.jetbrains:annotations:24.1.0")
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
