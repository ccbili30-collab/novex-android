plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":content-core"))
    implementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
