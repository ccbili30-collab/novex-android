plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":content-core"))
    implementation(project(":content-storage"))
    implementation(project(":conversation-core"))
    implementation(project(":model-transport"))
    implementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    useJUnit()
    // [T-bulk-tools-diag] 失败消息与测试 stdout 直达控制台（远端 CI 无法取报告时定位用）。
    testLogging {
        events("failed", "standardOut", "standardError")
        showExceptions = true
        showStackTraces = false
    }
}
