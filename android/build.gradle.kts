plugins {
    // AGP 9 는 Kotlin 지원이 내장이다 — kotlin.android 플러그인을 넣으면 오히려 에러.
    id("com.android.application") version "9.4.1" apply false
    // 준랩 폴드 패밀리(관제실·기록·에이전트)와 같은 Compose 컴파일러.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
