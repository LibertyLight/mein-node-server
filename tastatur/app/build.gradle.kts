import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.libertylight.tastatur"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.libertylight.tastatur"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            // Robolectric braucht die Android-Ressourcen, um Views auf dem Rechner zu zeichnen
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Keine Laufzeit-Bibliotheken: alles aus dem Android-SDK. Haelt die APK klein.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // Zeichnet die Tastatur auf dem Rechner mit echter Android-Grafik -- fuer den Vergleich mit dem Referenz-Screenshot
    testImplementation("org.robolectric:robolectric:4.17")
}
