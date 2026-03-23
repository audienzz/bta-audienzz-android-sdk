plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.audienzz.bta.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":sdk"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)

    // Expose lifecycle-runtime-compose transitively so publishers using BtaFeed
    // don't need to add it themselves (provides LocalLifecycleOwner, etc.).
    api(libs.androidx.lifecycle.runtime.compose)
}
