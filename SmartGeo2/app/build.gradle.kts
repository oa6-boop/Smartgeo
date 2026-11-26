plugins {
    alias(libs.plugins.android.application)

    id("com.google.gms.google-services") version "4.4.2"
}

android {
    namespace = "com.smartgeo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartgeo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.google.android.material:material:1.12.0")

    implementation(
        "com.google.android.gms:play-services-location:21.3.0"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"
    )

    implementation(
        "org.osmdroid:osmdroid-android:6.1.20"
    )

    implementation(
        "androidx.work:work-runtime-ktx:2.10.1"
    )

    // Firebase Cloud Messaging
    implementation(
        platform("com.google.firebase:firebase-bom:33.7.0")
    )

    implementation(
        "com.google.firebase:firebase-messaging"
    )
}