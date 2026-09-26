plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.fourj.iptv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.fourj.iptv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two flavors, split only on audio decoding.
    //
    // Many IPTV providers carry AC-3 / E-AC-3 / DTS / MP2 audio that low-end TV hardware cannot
    // decode. The symptom is a picture with no sound, which is indistinguishable from a dead
    // stream. The "full" flavor bundles the NextLib FFmpeg software decoders to fix that; "lite"
    // drops them for a much smaller APK and relies on hardware decode.
    flavorDimensions += "audio"
    productFlavors {
        create("full") {
            dimension = "audio"
        }
        create("lite") {
            dimension = "audio"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing is intentionally not configured yet. Sideloading a debug build is the
            // supported path until a release keystore is set up (see README).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    // Export the Room schema so migrations can be diffed and tested rather than guessed at.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose: tv-material is the primary toolkit - it gives correct D-pad focus and focus
    // visuals out of the box, which is the whole job on a television. Deliberately not using
    // androidx.compose.material3: two Material 3 namespaces in one build is a reliable way to
    // import the wrong one and get a phone-looking UI on a TV.
    //
    // The lists are the stable foundation LazyRow/LazyColumn rather than TvLazyRow/TvLazyColumn.
    // Those only exist in alpha/beta builds of androidx.tv:tv-foundation, and pulling an alpha
    // Compose artifact alongside a stable BOM is a compatibility problem waiting to happen. The
    // stable lists scroll focused items into view, which is the behaviour that matters here.
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    // Software audio decoders, only in the "full" flavor.
    "fullImplementation"(libs.nextlib.media3ext)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Local storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}
