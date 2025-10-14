group = "org.simplepoint.plugin"

dependencies {
    api(project(":simplepoint-plugin:simplepoint-plugin-api"))
    api(project(":simplepoint-plugin:simplepoint-plugin-core"))
    api(project(":simplepoint-plugin:simplepoint-plugin-spring"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-webmvc")
}