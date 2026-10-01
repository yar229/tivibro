plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version can be overridden from CI: ./gradlew -PappVersionName=1.2.3.4
// A tag like v1.2.3.4 is passed through by the release workflow.
val appVersionName: String = providers.gradleProperty("appVersionName").orNull?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "1.0.3"

// Android needs a monotonically increasing integer versionCode, so the dotted
// name is folded into one: each component must fit in 0..99.
// 1.2.3.4 -> ((1 * 100 + 2) * 100 + 3) * 100 + 4 = 1020304
val appVersionCode: Int = appVersionName.split(".").let { parts ->
    val components = (0..3).map { index ->
        val raw = parts.getOrNull(index).orEmpty()
        val value = if (raw.isEmpty()) 0 else raw.toIntOrNull()
            ?: throw GradleException("Invalid appVersionName '$appVersionName': component '$raw' is not a number")
        require(value in 0..99) {
            "Invalid appVersionName '$appVersionName': component '$raw' must be between 0 and 99"
        }
        value
    }
    if (parts.size > 4) {
        throw GradleException("Invalid appVersionName '$appVersionName': expected at most 4 components")
    }
    ((components[0] * 100 + components[1]) * 100 + components[2]) * 100 + components[3]
}

// Release signing is opt-in: when no keystore is configured the release APK
// stays unsigned, exactly as before.
val releaseStoreFile: String? =
    providers.gradleProperty("TIVIBRO_STORE_FILE").orNull?.takeIf { it.isNotEmpty() }
        ?: System.getenv("TIVIBRO_STORE_FILE")?.takeIf { it.isNotEmpty() }
val releaseStorePassword: String? =
    providers.gradleProperty("TIVIBRO_STORE_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
        ?: System.getenv("TIVIBRO_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseKeyAlias: String? =
    providers.gradleProperty("TIVIBRO_KEY_ALIAS").orNull?.takeIf { it.isNotEmpty() }
        ?: System.getenv("TIVIBRO_KEY_ALIAS")?.takeIf { it.isNotEmpty() }
val releaseKeyPassword: String? =
    providers.gradleProperty("TIVIBRO_KEY_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
        ?: System.getenv("TIVIBRO_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrEmpty() }

android {
    namespace = "com.tvibro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvibro"
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Only the languages the app is actually translated into are kept. Libraries drag their own
    // translations along otherwise. This is the supported form of the old
    // defaultConfig.resourceConfigurations, which is deprecated in favour of exactly this block.
    androidResources {
        localeFilters += setOf("en", "ru")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCY*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation(project(":media3-exoplayer-ffmpeg"))
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("org.videolan.android:libvlc-all:3.6.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.json:json:20231013")
}
