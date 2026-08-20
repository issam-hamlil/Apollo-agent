import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(rootProject)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

kotlin { jvmToolchain(17) }

// The core resolves .env, knowledge-base/, libs/android-stubs/, reports/ and migrated-src/
// relative to the process working directory. Gradle runs a subproject task from that
// subproject's folder, which would point every one of those at ui-desktop/. Pin it to the
// repo root so the UI and the CLI read and write exactly the same files.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

compose.desktop {
    application {
        mainClass = "com.issam.apollo.ui.AppKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Apollo Agent Console"
            packageVersion = "1.0.0"
        }
    }
}
