plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dsatm.audio_redaction"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    // Add Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)

    // Link to the core module
    implementation(project(":core"))

    implementation(project(":ner"))

    // Jetpack Compose dependencies
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Add this for compose previews
    debugImplementation("androidx.compose.ui:ui-tooling")
    // vosk implementation dependency
    implementation("com.alphacephei:vosk-android:0.3.70")

    // For AppCompatActivity (Provides the base class for your Activity)
    implementation("androidx.appcompat:appcompat:1.7.0")
// For ActivityResultContracts and ActivityResultLauncher (The modern way to get results from an Activity)
// Use the -ktx version for Kotlin projects
    implementation("androidx.activity:activity-ktx:1.9.0")

    implementation("org.json:json:20250517")
    // FFMPEG for audio beeping

    implementation("androidx.compose.material:material-icons-extended:1.7.0")






    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}