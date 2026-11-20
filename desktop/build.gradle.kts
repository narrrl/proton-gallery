import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Linux and Windows desktop app. Same Rust core and UniFFI bindings as the
// Android app; the native library is built for the host and loaded by JNA
// from the classpath. See docs/DESKTOP.md for the plan.
plugins {
    // Kotlin matches android/build.gradle.kts, so a shared UI module can join later.
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.12.2"
}

val workspace: File = rootProject.projectDir.parentFile
val generated = layout.buildDirectory.dir("generated")
val windows = System.getProperty("os.name").startsWith("Windows")

// JNA looks for the library under its platform prefix on the classpath.
val jnaPrefix = when {
    windows -> "win32-x86-64"
    System.getProperty("os.name") == "Linux" -> "linux-x86-64"
    else -> error("Desktop builds support Linux and Windows on x86_64")
}
val libraryFile = if (windows) "pg_mobile.dll" else "libpg_mobile.so"

val buildRust by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds the Rust core for this computer and generates its Kotlin bindings."
    workingDir(workspace)
    commandLine("bash", "scripts/build-desktop-native.sh", generated.get().asFile.absolutePath, jnaPrefix, libraryFile)
    inputs.files(fileTree(workspace.resolve("crates/pg-mobile")))
    inputs.files(workspace.resolve("Cargo.toml"), workspace.resolve("Cargo.lock"), workspace.resolve("scripts/build-desktop-native.sh"))
    outputs.dir(generated.map { it.dir("uniffi") })
    outputs.dir(generated.map { it.dir("native") })
}

sourceSets.main {
    kotlin.srcDir(generated.map { it.dir("uniffi") })
    resources.srcDir(generated.map { it.dir("native") })
}
tasks.named("compileKotlin") { dependsOn(buildRust) }
tasks.named("processResources") { dependsOn(buildRust) }

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0")
}

compose.desktop {
    application {
        mainClass = "io.narl.protongallery.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "proton-gallery"
            packageVersion = "0.3.0"
            description = "Your photos in Proton Drive"
            vendor = "Proton Gallery contributors"
            linux { iconFile.set(workspace.resolve("images/icon-512.png")) }
            windows {
                menuGroup = "Proton Gallery"
                // Stable across versions, so MSI upgrades replace the old install.
                upgradeUuid = "6f0c5a9e-58f4-4a3e-9d0b-7b6a2f3c1e42"
            }
        }
    }
}
