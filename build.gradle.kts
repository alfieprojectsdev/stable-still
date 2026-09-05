// Intentionally empty of plugin declarations.
//
// Declaring `alias(libs.plugins.android.application) apply false` here would
// resolve the Android Gradle Plugin marker for *every* build, including
// `gradle :core:test` on a machine with no Android SDK. Versions are already
// unified by gradle/libs.versions.toml, so each module applies its own plugins.

tasks.register("stableStillInfo") {
    group = "help"
    description = "Reports which modules are configured in this build."
    val modules = subprojects.map { it.path }
    doLast {
        println("stable-still modules: ${modules.joinToString(", ")}")
        if (modules.none { it == ":app" }) {
            println("  :app is absent - set ANDROID_HOME or add local.properties with sdk.dir.")
        }
    }
}
