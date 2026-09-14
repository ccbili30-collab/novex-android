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
tasks.test { useJUnit() }
