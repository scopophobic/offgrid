plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val offgridApiBaseUrl = (
    project.findProperty("OFFGRID_API_BASE_URL") as String?
    )?.trim()?.ifEmpty { null } ?: "https://offgrid-api.adithyanmadhu1234.workers.dev"

android {
    namespace = "com.offgrid.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offgrid.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "OFFGRID_API_BASE_URL",
            "\"$offgridApiBaseUrl\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // Large model assets should not be compressed during packaging.
        noCompress += listOf("pte")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Official Maven Central artifact per ExecuTorch Android docs.
    implementation("org.pytorch:executorch-android:1.1.0")

    // Pack catalog + ZIP download (more reliable on-device than HttpURLConnection).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
