group = "org.simplepoint.plugin"

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    api(project(":simplepoint-core"))
    api(project(":simplepoint-plugin:simplepoint-plugin-api"))
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.core:jackson-core")
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-toml")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-properties")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    api("org.slf4j:slf4j-api")

}