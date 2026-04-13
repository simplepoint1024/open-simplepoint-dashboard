group = "org.simplepoint"
version = "1.0.0"

allprojects {
    repositories {
        mavenLocal()
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/jcenter") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://repo.spring.io/milestone") }
        maven { setUrl("https://jitpack.io") }
        gradlePluginPortal()
        mavenCentral()
    }
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}