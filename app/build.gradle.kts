plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.eigger.hassble"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.eigger.hassble"
        minSdk = 26
        targetSdk = 35
        versionCode = 41
        versionName = "0.3.12"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            val keystorePass = System.getenv("KEYSTORE_PASS")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASS")
            if (keystoreFile != null && keystorePass != null && keyAlias != null && keyPass != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.api.ApkVariantOutput
            output.outputFileName = "HassBle-v${variant.versionName}-${variant.buildType.name}.apk"
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.browser)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.exp4j)
    implementation(libs.datastore.preferences)
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.scanner)
    implementation(libs.okhttp)

    testImplementation("junit:junit:4.13.2")
}
