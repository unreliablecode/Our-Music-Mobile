import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
val appVersionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
fun signingValue(key: String, environment: String): String? =
    providers.environmentVariable(environment).orNull ?: releaseSigningProperties.getProperty(key)
val releaseStore = signingValue("storeFile", "OUR_MUSIC_STORE_FILE")

android {
    namespace = "com.github.musicyou"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.omusic.unreliablecode"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionProperties.getProperty("VERSION_CODE").toInt()
        versionName = appVersionProperties.getProperty("VERSION_NAME")
        resValue("string", "application_id", "net.omusic.unreliablecode")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://raw.githubusercontent.com/unreliablecode/Our-Music-Mobile/main/update.json\"")
        buildConfigField("String", "REPOSITORY_URL", "\"https://github.com/unreliablecode/Our-Music-Mobile\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore != null) {
            create("ourMusicRelease") {
                storeFile = rootProject.file(releaseStore!!)
                storePassword = signingValue("storePassword", "OUR_MUSIC_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "OUR_MUSIC_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "OUR_MUSIC_KEY_PASSWORD")
            }
        }
    }

    splits {
        abi {
            reset()
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("ourMusicRelease")
        }

        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("ourMusicRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets.all {
        kotlin.directories.add("src/$name/kotlin")
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.activity)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.shimmer)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.material.motion.compose)
    implementation(libs.media)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.reorderable)
    implementation(libs.room)
    implementation(libs.swipe)
    ksp(libs.room.compiler)
    implementation(projects.github)
    implementation(projects.backdrop)
    implementation(projects.innertube)
    implementation(projects.kugou)
    coreLibraryDesugaring(libs.desugaring)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.concurrent:concurrent-futures:1.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
