pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Order matters: :core resolves entirely from Maven Central, so it never
        // touches the Google repo. That keeps :core buildable in CI images and
        // sandboxes that have no Android SDK and no access to dl.google.com.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
            }
        }
    }
}

rootProject.name = "stable-still"

include(":core")

// The Android application module needs an SDK and the Google Maven repo. Include
// it only when those are actually available, so that `gradle :core:test` works on
// a plain JDK box. Android Studio always satisfies this check.
val hasAndroidSdk = file("local.properties").let { lp ->
    lp.exists() && lp.readLines().any { it.trimStart().startsWith("sdk.dir") }
} || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle(
        "stable-still: no Android SDK detected - skipping :app. " +
            "Only the pure-JVM :core module is configured."
    )
}
