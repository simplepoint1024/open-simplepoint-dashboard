dependencies {
    implementation(project(":simplepoint-platform:simplepoint-platform-dictionary-registration"))
    runtimeOnly(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-module"))

    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))

    api(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-api"))
    api(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-repository"))
    api(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-service"))
}
