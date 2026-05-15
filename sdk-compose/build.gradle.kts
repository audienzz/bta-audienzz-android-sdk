import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    `signing`
}

android {
    namespace = "org.audienzz.bta.compose"
    compileSdk = 36
    version = "0.1.1"

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

signing {
    useGpgCmd()
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary())
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("com.audienzz", "bta-sdk-compose", version.toString())
    pom {
        name.set("BtaAudienzz Compose")
        description.set("Jetpack Compose wrapper for the Audienzz BTA feed SDK")
        inceptionYear.set("2026")
        url.set("https://github.com/audienzz/bta-audienzz-android-sdk/blob/main/README.md")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("tech@audienzz.ch")
                name.set("Audienzz")
                url.set("https://github.com/audienzz")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/audienzz/bta-audienzz-android-sdk.git")
            developerConnection.set("scm:git:ssh://github.com/audienzz/bta-audienzz-android-sdk.git")
            url.set("https://github.com/audienzz/bta-audienzz-android-sdk")
        }
    }
}
