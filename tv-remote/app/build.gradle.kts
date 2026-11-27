import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload-key details live in keystore.properties (never committed). Without it,
// release builds fall back to the debug key so the project still builds anywhere.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.redemption.tvremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.redemption.remoteplus"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
