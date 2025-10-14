rootProject.name = "simplepoint-main"
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("./buildSrc/libs.versions.toml"))
        }
    }
}

pluginManagement {
    repositories {
        mavenLocal()
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://repo.spring.io/release") }
    }
}

fileTree(rootDir) {
    val excludes = gradle.startParameter.projectProperties["excludeProjects"]?.split(",")
    include("**/*.gradle.kts")
    exclude("build", "**/gradle", "settings.gradle", "buildSrc", "/build.gradle", ".*", "out")
    if (excludes != null) {
        if (excludes.isNotEmpty()) {
            exclude(excludes)
        }
    }
}.forEach {
    include(
        it.path
            .replace(rootDir.absolutePath, "")
            .replace(File.separatorChar + it.name, "")
            .replace(File.separatorChar, ':')
    )
}