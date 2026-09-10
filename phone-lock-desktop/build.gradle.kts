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
    // 96차: 그룹 카드의 자물쇠 잠금/해제 아이콘(Lock/LockOpen)이 기본 material-icons-core엔 없어 추가.
    implementation(compose.materialIconsExtended)
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
    // 98차 발견: outputs만 선언되고 inputs가 없어서, 같은 build/ 디렉터리에서 여러 번 빌드하면 Gradle이
    // 이 태스크를 "이미 실행한 적 있음"으로 보고 UP-TO-DATE로 건너뛰어 몇 세션 전 타임스탬프가 그대로
    // 남는 버그가 있었다(자체 업데이트 태그/버전 비교가 이 값에 의존하므로 실제로 새 빌드인데 옛 버전으로
    // 보고될 위험) — 항상 다시 실행해서 매 빌드마다 새 타임스탬프를 굽도록 강제한다.
    outputs.upToDateWhen { false }
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

        // jvmToolchain(21)이 만드는 Java 21 바이트코드(클래스 버전 65)를 Compose Multiplatform 1.6.11이
        // 기본으로 받아오는 ProGuard 7.2.2(최대 Java 18=버전 62)가 못 읽어서 releaseMsi/releaseExe 빌드가
        // 항상 실패하던 버그(2026-09-05 발견) — ProGuard 버전을 Java 21을 지원하는 7.4.2로 올려 해결.
        buildTypes.release.proguard {
            version.set("7.4.2")
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

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
