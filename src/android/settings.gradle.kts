val preferCanonicalRepositories =
    System.getenv("NOVEX_PREFER_CANONICAL_REPOSITORIES").equals("true", ignoreCase = true)

pluginManagement {
    repositories {
        if (preferCanonicalRepositories) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        // Windows builders in mainland China may fail the TLS handshake to
        // Google's repositories even when ordinary HTTPS works. Keep the
        // canonical repositories below, but put reproducible mirrors first.
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        if (!preferCanonicalRepositories) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (preferCanonicalRepositories) {
            google()
            mavenCentral()
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        if (!preferCanonicalRepositories) {
            google()
            mavenCentral()
        }
        // [T-android-vad] RealTimeCutVADLibraryForAndroid ships via JitPack
        // only. Same author and same underlying stack (Silero + ONNX Runtime +
        // WebRTC APM) as the RealTimeCutVADLibrary SPM package iOS already
        // uses, so both platforms segment speech with the same model and the
        // same tunables.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Noven"
include(":app")
