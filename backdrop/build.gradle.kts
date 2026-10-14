plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.kyant.backdrop"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.ui)
    implementation("androidx.compose.ui:ui-graphics")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("org.jetbrains:annotations:26.1.0")
}
