subprojects {
    group = "org.simplepoint.services"
    dependencies{
        implementation("mysql:mysql-connector-java:8.0.33")
        implementation("org.postgresql:postgresql:42.7.5")
    }
}