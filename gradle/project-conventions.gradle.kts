// Shared conventions formerly held by source-free parent projects.
// Keep existing Maven groups independently of the Gradle project hierarchy.
val moduleDirectory = rootProject.relativePath(projectDir).replace(File.separatorChar, '/')
val domainGroups = linkedMapOf(
    "simplepoint-plugins/simplepoint-plugin-ai/" to "org.simplepoint.plugins.ai",
    "simplepoint-plugins/simplepoint-plugin-dna/" to "org.simplepoint.plugins.dna",
    "simplepoint-plugins/simplepoint-plugin-notification/" to "org.simplepoint.plugins.notification",
    "simplepoint-plugins/simplepoint-plugins-auditing/" to "org.simplepoint.plugins.audit",
    "simplepoint-plugins/simplepoint-plugins-i18n/" to "org.simplepoint.plugins.i18n",
    "simplepoint-plugins/simplepoint-plugins-oidc/" to "org.simplepoint.plugins.oidc",
    "simplepoint-plugins/simplepoint-plugins-rbac/" to "org.simplepoint.plugins.rbac",
    "simplepoint-plugins/simplepoint-plugins-storage/" to "org.simplepoint.plugins.storage",
    "simplepoint-data/simplepoint-data-calcite/" to "org.simplepoint.data.calcite",
    "simplepoint-data/simplepoint-data-json/" to "org.simplepoint.data.json",
    "simplepoint-examples/simplepoint-plugin-examples/" to "org.simplepoint.example.plugin",
    "simplepoint-examples/simplepoint-service-router-examples/" to "org.simplepoint.example.router",
    "simplepoint-boot/" to "org.simplepoint.boot",
    "simplepoint-cache/" to "org.simplepoint.cache",
    "simplepoint-data/" to "org.simplepoint.data",
    "simplepoint-plugin/" to "org.simplepoint.plugin",
    "simplepoint-security/" to "org.simplepoint.security",
    "simplepoint-services/" to "org.simplepoint.services",
    "simplepoint-data-extend/" to "org.simplepoint.data.extend",
    "simplepoint-examples/" to "org.simplepoint.example",
    "simplepoint-platform/" to "simplepoint-main.simplepoint-platform",
    "simplepoint-remoting/" to "simplepoint-main.simplepoint-remoting",
    "simplepoint-service-router/" to "simplepoint-main.simplepoint-service-router"
)
group = domainGroups.entries.firstOrNull { moduleDirectory.startsWith(it.key) }?.value
    ?: "org.simplepoint"

if (moduleDirectory.startsWith("simplepoint-services/")) {
    dependencies {
        add("implementation", "mysql:mysql-connector-java:8.0.33")
        add("implementation", "org.postgresql:postgresql:42.7.5")
    }
}
