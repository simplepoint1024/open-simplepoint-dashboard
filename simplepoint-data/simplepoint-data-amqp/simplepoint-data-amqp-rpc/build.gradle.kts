dependencies {
    implementation(project(":simplepoint-core"))
    implementation("com.esotericsoftware:kryo:5.6.2")
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-core"))
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-annotation"))
}