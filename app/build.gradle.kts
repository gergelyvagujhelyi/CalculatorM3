import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

kotlin {
    compilerOptions {
        // Kotlin 2.4 removed the android { kotlinOptions { jvmTarget = "17" } } form.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.vagujhelyigergely.calculatorm3"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.vagujhelyigergely.calculatorm3"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Custom-scheme redirect for the HuggingFace OAuth flow. AppAuth merges its
        // RedirectUriReceiverActivity with an intent-filter for this scheme, so no
        // manual <activity> is needed. Must match HuggingFaceAuth.REDIRECT_URI's scheme
        // and the redirect URI registered on the HF OAuth app.
        manifestPlaceholders["appAuthRedirectScheme"] = "calculatorm3"

        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // LiteRT-LM ships 64-bit ARM native libraries; this is the only ABI we support.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // `-PdeviceTest` builds the debug app as a separate package (….devicetest), so
            // on-device integration tests that write app data run in their own sandbox and never
            // touch the real app's login, history or models on a personal phone.
            if (project.hasProperty("deviceTest")) applicationIdSuffix = ".devicetest"
        }
        release {
            isMinifyEnabled = true
            // Sign only when keystore.properties exists (local release builds, and the release
            // workflow, which writes it from secrets). Without it — e.g. F-Droid's reproducible
            // build from source — Gradle produces an unsigned app-release-unsigned.apk instead of
            // failing in packageRelease. The release workflow separately refuses to publish an
            // APK that isn't signed with the release certificate, so this can't ship unsigned.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures {
        compose = true
    }
    lint {
        // ChromeOsAbiSupport asks for an x86_64 build. LiteRT-LM (the on-device AI engine) ships
        // only arm64-v8a native libraries, so x86_64 isn't possible (see abiFilters above).
        disable += "ChromeOsAbiSupport"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/version-control-info.textproto"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // material3 1.5.0-alpha brings the M3-Expressive loaders (LoadingIndicator,
    // LinearWavyProgressIndicator) used by the AI views — they are NOT in 1.4.0 stable.
    // alpha14 is the last 1.5.0 alpha that still targets Compose 1.8/1.9 (the BOM above);
    // alpha16+ requires Compose 1.11+. Pinned explicitly to override the BOM's material3.
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // EXIF orientation of scanned photos; the framework android.media.ExifInterface has known
    // parsing bugs on older Android versions (minSdk is 26).
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // HuggingFace OAuth (authorization-code + PKCE) for gated model downloads.
    // Handles the Custom Tab, redirect capture, and code→token exchange; pulls in androidx.browser.
    implementation("net.openid:appauth:0.11.1")
    // Markdown + LaTeX rendering of the model's answer (ext-latex pulls in jlatexmath).
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
