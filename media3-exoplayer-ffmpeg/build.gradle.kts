plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-decoder:1.3.1")
    implementation("androidx.annotation:annotation:1.8.0")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
}
