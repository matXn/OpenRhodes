plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

tasks.register("runtimeInventory") {
    doLast {
        val artifacts =
            configurations
                .getByName("debugRuntimeClasspath")
                .resolvedConfiguration
                .resolvedArtifacts
        val output = layout.buildDirectory.file("runtime-artifacts.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            artifacts
                .sortedBy { it.moduleVersion.id.toString() }
                .joinToString("\n") {
                    "${it.moduleVersion.id}\t${it.file.absolutePath}"
                }
        )
    }
}

android {
    namespace = "com.openrhodes"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.openrhodes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 51
        versionName = "2.1.26-dev"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(
        "androidx.datastore:datastore-preferences:1.0.0"
    ) // legacy widget state import only
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.crypto.tink:tink-android:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
