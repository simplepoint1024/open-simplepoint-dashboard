subprojects {
    group = "org.simplepoint.example.amqprpc.api"
}

dependencies {
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
    api(project(":simplepoint-data-amqp-rpc"))
}
