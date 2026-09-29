import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// release 서명 정보는 git에 커밋되지 않는 keystore.properties(67차)에서 읽는다 - 없으면 release는 unsigned로 빌드됨.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

// 다중 로그인(140차): google-services.json의 웹 클라이언트 ID(oauth_client, client_type 3)를 BuildConfig로 넘긴다
// (출시용 프로젝트와 같은 방식). 비어 있으면 앱은 구글 로그인 버튼을 숨긴다 — google-services 플러그인의
// R.string.default_web_client_id는 값이 없으면 생성되지 않아 컴파일이 깨지므로 직접 읽는다.
val googleWebClientId: String = run {
    val json = file("google-services.json")
    if (!json.exists()) return@run ""
    @Suppress("UNCHECKED_CAST")
    val clients = (groovy.json.JsonSlurper().parse(json) as Map<String, Any?>)["client"] as? List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    clients.orEmpty()
        .filter { ((it["client_info"] as? Map<String, Any?>)?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == "com.phonelock.app" }
        .flatMap { (it["oauth_client"] as? List<Map<String, Any?>>).orEmpty() }
        .firstOrNull { it["client_type"]?.toString() == "3" }
        ?.get("client_id")?.toString().orEmpty()
}

android {
    namespace = "com.phonelock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonelock.app"
        minSdk = 26
        targetSdk = 34
        // 빌드할 때마다 자동으로 증가시켜서, 기존 앱을 지우지 않고도 새 APK를 그냥 설치(업데이트)할 수 있게 한다.
        versionCode = (System.currentTimeMillis() / 1000).toInt()
        versionName = "1.0"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 82차(감사 §7 ":shared" 도입 후 발견): AGP의 Lint 모델이 composite build(includeBuild)로 연결된
    // :shared 모듈의 jar를 못 찾아 "Could not find jar for project :shared" 에러로 release 빌드 자체가
    // 막힘(AGP의 알려진 한계 — lint의 아티팩트 해석이 substituted project dependency를 못 다룸).
    // lintVital을 release 조립 태스크에서 분리 — lint 자체는 여전히 `gradle lint`로 수동 실행 가능.
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // 82차(감사 §7 ":shared"): CalcEngine/문구 등 순수 로직을 데스크탑과 공유하는 composite build 모듈.
    implementation("com.phonelock:shared")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    // 다중 로그인(140차): 구글 로그인 — Credential Manager + Sign in with Google(출시용과 같은 버전).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 82차(감사 TOP20 20위, §14): LockEvaluator/ConfirmationGate 판정 로직 최소 유닛테스트용(로컬 JVM 테스트).
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
