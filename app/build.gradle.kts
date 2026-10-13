plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.mdinstal.narzedzia"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.mdinstal.narzedzia"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = "mdinstal2026"
            keyAlias = "mdinstal"
            keyPassword = "mdinstal2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val vName = versionName ?: "1.0"
            val vCode = versionCode.toString()
            output.outputFileName = "MD-INSTAL-${vName}-${vCode}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
