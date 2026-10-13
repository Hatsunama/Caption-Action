plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.hatsunama.captionaction.captureprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hatsunama.captionaction.captureprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-head28ac"
    }
    buildTypes { debug { signingConfig = null }; release { signingConfig = null } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
// No install, signing, key generation or device execution from cloud.
gradle.taskGraph.whenReady {
    allTasks.forEach { task ->
        check(task.name == "writeDebugSigningConfigVersions" || !Regex("(?i)(install|uninstall|connected|sign|keytool|keystore)").containsMatchIn(task.name)) {
            "Capture probe forbids signing/key/device tasks: ${task.path}"
        }
    }
    check(android.buildTypes.toList().all { it.signingConfig == null })
}
