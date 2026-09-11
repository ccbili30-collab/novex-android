plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":conversation-core"))
    implementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }

tasks.register<JavaExec>("anonymousProbe") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("novex.model.AnonymousProbe")
    doFirst {
        args(project.property("probeModel"), project.property("probeWindow"), project.property("probeOutput"))
    }
}
