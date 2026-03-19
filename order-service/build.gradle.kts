plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared:retry-infrastructure"))
    implementation(project(":shared:outbox-events"))

    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

// Testcontainers: point to the Colima socket when Docker Desktop is not running.
// Falls back to the default /var/run/docker.sock on Linux / Docker Desktop setups.
tasks.withType<Test> {
    val colimaSocket = File(System.getProperty("user.home"), ".colima/default/docker.sock")
    if (colimaSocket.exists()) {
        // DOCKER_HOST: how the JVM process on macOS connects to the Colima daemon
        environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
        // TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: path mounted *inside* the Colima VM containers
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}
