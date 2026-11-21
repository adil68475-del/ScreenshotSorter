plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sorter.shots"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sorter.shots"
        minSdk = 29
        targetSdk = 33
        versionCode = 2
        versionName = "2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}
