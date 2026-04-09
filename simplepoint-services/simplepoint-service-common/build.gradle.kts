plugins {
    application
}

application {
    mainClass.set("org.simplepoint.common.server.Common")
}

dependencies {
    implementation(project(":simplepoint-boot:simplepoint-boot-starter"))
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-security:simplepoint-security-servlet"))
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    implementation(project(":simplepoint-cache:simplepoint-cache-redis"))

    // 引入AMQP RPC支持
    implementation(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-rpc"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    // 引入RBAC权限体系核心插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-rest"))
    // 引入RBAC权限体路由插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-rest"))
    // 引入OIDC体系插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-rest"))

    // 引入国际化多语言插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-rest"))

    // 引入租户管理插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-rest"))

    // 引入对象存储插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-s3"))

    // 引入初始化数据插件
    implementation(project(":simplepoint-data:simplepoint-data-initializer"))

    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))

    // 引入API文档
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
}
