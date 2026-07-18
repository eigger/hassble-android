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
        versionCode = 56
        versionName = "1.0.4"
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
    packaging {
        jniLibs {
            // datastore-preferences가 포함하는 네이티브 라이브러리:
            // NDK strip 툴이 처리하지 못해 경고가 발생하므로 suppress
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
        }
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

// AAB(Android App Bundle) 출력 파일명을 APK와 동일한 형식으로 맞춘다.
// ApkVariantOutput API는 AAB에 적용되지 않으므로 bundleXxx 태스크 완료 후 rename.
// 결과: HassBle-v0.3.21-release.aab / HassBle-v0.3.21-debug.aab
tasks.whenTaskAdded {
    if (name.startsWith("bundle") && (name.endsWith("Release") || name.endsWith("Debug"))) {
        val buildTypeName = when {
            name.endsWith("Release") -> "release"
            else -> "debug"
        }
        doLast {
            val outDir = project.layout.buildDirectory
                .dir("outputs/bundle/$buildTypeName")
                .get().asFile
            val versionName = android.defaultConfig.versionName
            outDir.listFiles()
                ?.firstOrNull { it.extension == "aab" }
                ?.renameTo(File(outDir, "HassBle-v${versionName}-${buildTypeName}.aab"))
        }
    }
}
