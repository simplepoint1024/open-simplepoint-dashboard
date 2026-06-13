dependencies {
  api(project(":simplepoint-service-router:simplepoint-service-router-core"))
  api("org.springframework.cloud:spring-cloud-starter-consul-discovery")
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}
