dependencies {
    api(project(":simplepoint-data:simplepoint-data-migrations-api"))
    testImplementation(platform("org.flowable:flowable-bom:${rootProject.libs.versions.flowable.get()}"))
    testImplementation("org.flowable:flowable-engine")
}
