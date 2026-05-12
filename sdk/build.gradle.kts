import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    `signing`
}

android {
    namespace = "org.audienzz.bta.sdk"
    compileSdk = 36
    version = "0.1.0"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}

signing {
    useGpgCmd()
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary())
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("com.audienzz", "bta-sdk", version.toString())
    pom {
        name.set("BtaAudienzz")
        description.set("Android BTA (Below The Article) feed SDK by Audienzz")
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
