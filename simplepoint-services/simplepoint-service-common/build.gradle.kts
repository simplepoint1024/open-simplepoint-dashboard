dependencies {
    api(project(":simplepoint-core"))
    api(project(":simplepoint-boot:simplepoint-boot-starter-web"))
    api(project(":simplepoint-cloud:simplepoint-cloud-consul"))
    api(project(":simplepoint-cloud:simplepoint-cloud-loadbalancer"))
    api(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-data:simplepoint-data-jpa"))
    api(project(":simplepoint-data:simplepoint-data-redis"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    api(project(":simplepoint-security:simplepoint-security-oauth2-resource"))

    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-rpc"))
    api(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))
    // 引入RBAC权限体系核心插件
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-repository"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-service"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-rest"))
    // 引入RBAC权限体路由插件
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-service"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-rest"))
    // 引入OIDC体系插件
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-repository"))
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-service"))
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-rest"))

    // 引入国际化多语言插件
    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-repository"))
    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-service"))
    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-rest"))

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
}