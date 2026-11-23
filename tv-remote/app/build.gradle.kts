plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.portfolio.tvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.portfolio.tvremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK can be sideloaded directly.
            // Replace with your own signing config before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    // Used only to create the self-signed client certificate the TV pairs with.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation("junit:junit:4.13.2")
}
