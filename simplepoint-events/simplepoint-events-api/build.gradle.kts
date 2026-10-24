dependencies {
    api(platform("com.fasterxml.jackson:jackson-bom:${rootProject.libs.versions.jackson.get()}"))
    api(project(":simplepoint-platform:simplepoint-platform-composition"))
    api("com.fasterxml.jackson.core:jackson-databind")
}
