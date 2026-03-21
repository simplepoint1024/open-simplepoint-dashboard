allprojects {
    group = "org.simplepoint.cache"
}
subprojects {
    apply(plugin = "java-library")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    api(project(":simplepoint-cache:simplepoint-cache-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

}