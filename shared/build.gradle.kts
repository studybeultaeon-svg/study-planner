// 안드로이드/데스크탑 어느 쪽 UI 프레임워크에도 의존하지 않는 순수 Kotlin 로직만 담는 모듈(82차,
// 감사 §7 ":shared 순수 로직 공유 모듈"). 두 앱은 완전히 분리된 Gradle 루트 프로젝트라 진짜 공유는
// composite build(각 프로젝트의 settings.gradle.kts에서 includeBuild("../shared"))로 연결한다.
// 여기 담을 수 있는 건 java.time/kotlin stdlib 외에 아무것도 참조하지 않는 파일뿐 — Room 엔티티,
// Compose 테마, 플랫폼별 데이터 클래스에 의존하는 로직(LockEvaluator, RoutineEngine 등)은 대상이 아니다.
plugins {
    kotlin("jvm") version "1.9.24"
    `java-library`
}

group = "com.phonelock"
version = "1.0.0"

repositories {
    mavenCentral()
}

// 안드로이드 앱 모듈이 jvmTarget=1.8로 고정돼 있어(app/build.gradle.kts) 이 모듈도 그에 맞춘다 —
// 데스크탑(jvmToolchain 21)은 더 낮은 바이트코드 버전을 그대로 소비할 수 있어 문제없다.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
