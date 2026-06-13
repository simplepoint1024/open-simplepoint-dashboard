plugins {
  application
}

application {
  mainClass.set("org.simplepoint.router.example.provider.ProviderApplication")
}

dependencies {
  implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-actuator")

  implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
  implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
  implementation(project(":simplepoint-examples:simplepoint-service-router-examples:simplepoint-service-router-example-api"))
}
