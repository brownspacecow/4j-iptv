import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val versionProps = Properties().apply {
    load(file("../version.properties").inputStream())
}
val verCode = versionProps.getProperty("versionCode").toInt()
val verName = versionProps.getProperty("versionName")

android {
    namespace = "com.iptv.fourj"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iptv.fourj"
        minSdk = 23
        targetSdk = 36
        versionCode = verCode
        versionName = verName
    }

    signingConfigs {
        val envStore = System.getenv("KEYSTORE_PATH")
        val envPass = System.getenv("KEYSTORE_PASSWORD")
        val envAlias = System.getenv("KEY_ALIAS")
        val envKeyPass = System.getenv("KEY_PASSWORD")

        if (envStore != null && envPass != null && envAlias != null && envKeyPass != null) {
            create("release") {
                storeFile = file(envStore)
                storePassword = envPass
                keyAlias = envAlias
                keyPassword = envKeyPass
            }
        } else {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { load(propsFile.inputStream()) }
                val sf = props.getProperty("storeFile")
                val sp = props.getProperty("storePassword")
                val ka = props.getProperty("keyAlias")
                val kp = props.getProperty("keyPassword")
                if (sf != null && sp != null && ka != null && kp != null) {
                    create("release") {
                        storeFile = rootProject.file(sf)
                        storePassword = sp
                        keyAlias = ka
                        keyPassword = kp
                    }
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-okhttp:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
}
