import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.phonelock.desktop"
version = "1.0.0"

dependencies {
    // 82차(감사 §7 ":shared"): CalcEngine/문구 등 순수 로직을 안드로이드와 공유하는 composite build 모듈.
    implementation("com.phonelock:shared")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.json:json:20240303")

    // 82차(감사 TOP20 20위, §14): LockEvaluator/ConfirmationGate 판정 로직 최소 유닛테스트용.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.mockk:mockk:1.13.11")
}

tasks.test {
    useJUnitPlatform()
}

// 안드로이드 versionCode(빌드 시각 자동 증가)와 같은 방식 — 자체 업데이트 확인(BuildInfo.BUILD_TIMESTAMP)이
// 매 빌드마다 수동으로 버전을 올리지 않아도 항상 이전 빌드보다 큰 값을 갖도록 컴파일 시점에 생성한다.
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")

val generateBuildInfo by tasks.registering {
    val outputDir = generatedBuildInfoDir
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("com/phonelock/desktop")
        pkgDir.mkdirs()
        val timestamp = System.currentTimeMillis() / 1000
        File(pkgDir, "BuildInfo.kt").writeText(
            """
            |package com.phonelock.desktop
            |
            |object BuildInfo {
            |    const val BUILD_TIMESTAMP = ${timestamp}L
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
    }
}

tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

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
                shortcut = true
                menu = true
                menuGroup = "PhoneLockDesktop"
            }
        }
    }
}
