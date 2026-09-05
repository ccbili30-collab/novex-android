plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Android supplies org.json at runtime. The standalone core tests use the matching JVM jar.
    compileOnly("org.json:json:20231013")
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
