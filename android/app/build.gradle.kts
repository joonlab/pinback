import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    // AGP 9 부터 Kotlin 은 AGP 내장 — kotlin.android 플러그인은 넣지 않는다.
    id("org.jetbrains.kotlin.plugin.compose")
}

// 개인 설정(기본 차 블루투스 이름)은 저장소에 넣지 않는다.
// 우선순위: gradle 속성 -Ppinback.defaultCarName=... > android/local.properties > 빈 값
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultCarName: String = (findProperty("pinback.defaultCarName") as String?)
    ?: localProps.getProperty("pinback.defaultCarName") ?: ""
val defaultCarNameLiteral = "\"" + defaultCarName.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "kr.joonlab.pinback"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joonlab.pinback"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val stamp = SimpleDateFormat("MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$stamp\"")
        buildConfigField("String", "DEFAULT_CAR_NAME", defaultCarNameLiteral)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes { release { isMinifyEnabled = false } }
}

// 저장소는 JSON 파일 하나 — Room 없음.
// BOM 2026.08+ 는 compileSdk 37 을 요구한다 — 2026.06.01 고정.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
}
