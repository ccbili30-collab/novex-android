pluginManagement {
    val preferCanonicalRepositories =
        System.getenv("NOVEX_PREFER_CANONICAL_REPOSITORIES").equals("true", ignoreCase = true)
    repositories {
        if (preferCanonicalRepositories) {
            mavenCentral()
            gradlePluginPortal()
        }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        if (!preferCanonicalRepositories) {
            mavenCentral()
            gradlePluginPortal()
        }
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
    }
}

dependencyResolutionManagement {
    val preferCanonicalRepositories =
        System.getenv("NOVEX_PREFER_CANONICAL_REPOSITORIES").equals("true", ignoreCase = true)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (preferCanonicalRepositories) {
            mavenCentral()
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        if (!preferCanonicalRepositories) {
            mavenCentral()
        }
    }
}

rootProject.name = "NovexCore"
