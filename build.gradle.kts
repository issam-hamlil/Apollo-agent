plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    // Declared (not applied) here so the :ui-desktop module can apply them without a version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    application
}

group = "com.issam.apollo"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Koog core
    implementation("ai.koog:koog-agents:1.0.0")

    // Java AST parsing (for Analyzer)
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.2")

    // Kotlin compiler embeddable (for Verifier — compiling generated Kotlin)
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    // Ktor — HTTP server layer (McpServer)
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Serialization for state/report objects
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Environment loading
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // Ktlint engine for lint verification
    implementation("com.pinterest.ktlint:ktlint-rule-engine:1.3.1")
    implementation("com.pinterest.ktlint:ktlint-ruleset-standard:1.3.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.issam.apollo.MainKt")
}
