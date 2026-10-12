plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hatsunama.captionaction"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hatsunama.captionaction"
        minSdk = 26
        targetSdk = 36
        versionCode = 51
        versionName = "0.3.28"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Production credentials stay off this build. Sign reviewed artifacts separately.
            signingConfig = null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(files("libs/whisper-android-1.0.0.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
}

// Separate destinations avoid confusing a Play upload bundle with a Solana APK.
// Both use the same existing applicationId/version and remain deliberately unsigned.
tasks.register<Copy>("preparePlayAab") {
    group = "distribution"
    description = "Build and stage the unsigned Google Play upload bundle."
    dependsOn("bundleRelease")
    from(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
    into(layout.buildDirectory.dir("distribution/play"))
    rename { "caption-action-play-unsigned.aab" }
    doFirst { check(android.buildTypes.getByName("release").signingConfig == null) }
}

tasks.register<Copy>("prepareSolanaApk") {
    group = "distribution"
    description = "Build and stage the unsigned Solana distribution APK."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
    into(layout.buildDirectory.dir("distribution/solana"))
    rename { "caption-action-solana-unsigned.apk" }
    doFirst { check(android.buildTypes.getByName("release").signingConfig == null) }
}
