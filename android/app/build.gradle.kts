import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeAbis = providers.environmentVariable("PG_ANDROID_ABIS").orElse("arm64-v8a,x86_64").get().split(",")
require(nativeAbis.isNotEmpty() && nativeAbis.all { it in setOf("arm64-v8a", "x86_64") }) { "Unsupported PG_ANDROID_ABIS" }

android {
    namespace = "io.narl.protongallery"
    compileSdk = 36
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "io.narl.protongallery"
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 100
        versionName = "0.1.0"
        ndk { abiFilters += nativeAbis }
    }
    testBuildType = "device"
    buildFeatures { compose = true }
    sourceSets.named("main") {
        java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
    }
    // Keys stay outside the checkout: scripts/build-signed-android.py and CI
    // pass them in the environment. Without them, these builds are unsigned.
    signingConfigs {
        create("fromEnvironment") {
            providers.environmentVariable("PG_ANDROID_KEYSTORE").orNull?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("PG_ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("PG_ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("PG_ANDROID_KEY_PASSWORD").orNull
            storeType = "PKCS12"
        }
    }
    buildTypes {
        create("device") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testing"
            versionNameSuffix = "-testing"
            resValue("string", "app_name", "Proton Gallery Testing")
            signingConfig = signingConfigs.getByName("fromEnvironment")
            matchingFallbacks += listOf("debug")
        }
        release {
            if (providers.environmentVariable("PG_ANDROID_KEYSTORE").isPresent) signingConfig = signingConfigs.getByName("fromEnvironment")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("jna.library.path", rootProject.projectDir.parentFile.resolve("target/debug").absolutePath)
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        abortOnError = true
        informational += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

val buildRust by tasks.registering(Exec::class) {
    group = "native"
    workingDir(rootProject.projectDir.parentFile)
    commandLine("bash", "scripts/build-android.sh", "native")
    // Native tools never need signing credentials, including in diagnostic output.
    environment = environment.filterKeys { it !in setOf("PG_ANDROID_STORE_PASSWORD", "PG_ANDROID_KEY_PASSWORD") }
    inputs.property("nativeAbis", nativeAbis)
    inputs.files(fileTree("../../crates/pg-core/src"), fileTree("../../crates/pg-drive/src"), fileTree("../../crates/pg-mobile"))
    inputs.files("../../Cargo.toml", "../../Cargo.lock", "../../crates/pg-core/Cargo.toml", "../../crates/pg-drive/Cargo.toml", "../../scripts/build-android.sh")
    outputs.dir(layout.buildDirectory.dir("generated/uniffi"))
    outputs.dir(layout.buildDirectory.dir("generated/jniLibs"))
}
tasks.named("preBuild") { dependsOn(buildRust) }

dependencies {
    implementation("rustls:rustls-platform-verifier:0.1.1@aar")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.11.3")
    implementation("androidx.compose.foundation:foundation:1.11.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.11.3")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    // Host-only JNA native resources exercise the generated Kotlin/Rust ABI.
    testRuntimeOnly("net.java.dev.jna:jna:5.17.0@jar")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.3")
}
