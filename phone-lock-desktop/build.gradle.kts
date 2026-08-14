import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.phonelock.desktop"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.json:json:20240303")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.phonelock.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "PhoneLockDesktop"
            packageVersion = "1.0.0"
            modules(
                "java.base", "java.desktop", "java.logging", "java.prefs",
                "java.datatransfer", "java.xml", "java.naming", "java.net.http",
                "jdk.httpserver", "jdk.unsupported", "jdk.unsupported.desktop",
                "jdk.crypto.ec", "jdk.crypto.mscapi"
            )
            windows {
                iconFile.set(project.file("packaging/app-icon.ico"))
            }
        }
    }
}
