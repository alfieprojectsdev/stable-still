import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is deliberately free of Android dependencies. Everything that decides
// *where a pixel goes* lives here so it can be tested on a laptop in
// milliseconds instead of on a phone in minutes.
dependencies {
    testImplementation(libs.junit)
}

// Target 17 bytecode (what AGP 8.x expects) without pinning a toolchain, so the
// module still builds on a box that only has a newer JDK installed.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
    // Forward the burst directory into the forked test JVM. A -D on the command
    // line reaches the Gradle daemon and stops there, so without this the
    // pixel-level replay tests skip silently and look like they passed.
    System.getProperty("stablestill.burstDir")?.let {
        systemProperty("stablestill.burstDir", it)
    }
}
