plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.readiness.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readiness.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "3.3"
        vectorDrawables { useSupportLibrary = true }
    }

    /* Fester Debug-Schlüssel statt des automatisch erzeugten.
       Jeder GitHub-Actions-Lauf startet auf einem frischen Rechner und legt sich sonst
       einen NEUEN Debug-Keystore an. Android verweigert dann jede Aktualisierung mit
       INSTALL_FAILED_UPDATE_INCOMPATIBLE, weil die Signatur nicht zur installierten
       Version passt — man müsste die App vor jedem Update deinstallieren und verlöre
       dabei API-Key, Einstellungen und den Kraftdaten-Cache. Mit einem mitgelieferten
       Schlüssel sind alle Builds signaturgleich und lassen sich normal überschreiben.
       Das ist KEIN Freigabeschlüssel: er taugt nur zum Sideload, nicht für den Play Store. */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
