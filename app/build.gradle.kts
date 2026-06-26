import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.zip.ZipFile

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
        versionCode = 12
        versionName = "1.5.1"

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
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
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
    lint {
        // The bumped AndroidX libraries (Compose 1.9 / lifecycle) ship lint checks built
        // against a newer lint-api than AGP 8.7.3 bundles, so the built-in
        // NonNullableMutableLiveDataDetector throws IncompatibleClassChangeError mid-analysis
        // and aborts the release-only lintVital task. It's a tooling-version crash, not a code
        // finding; disabling its issue id makes lint skip that one detector (its UAST visitor
        // never runs) while every other lint check keeps working.
        disable += "NullSafeMutableLiveData"
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
            // qnn-runtime ships every Hexagon arch (v68–v81) plus an 82 MB JIT "Prepare" lib. The S25
            // is Hexagon v79 and runs an AOT-precompiled model, so keep only libQnnHtp/libQnnSystem and
            // the v79 Stub+Skel (~22 MB) and drop the rest. To support another SoC, keep its vXX pair.
            excludes += "**/libQnnHtpPrepare.so"
            excludes += "**/libQnnGpu.so"
            excludes += "**/libQnnDsp.so"
            excludes += "**/libQnnDspV66Stub.so"
            excludes += "**/libQnnDspV66Skel.so"
            excludes += "**/libQnnHtpV68Stub.so"
            excludes += "**/libQnnHtpV68Skel.so"
            excludes += "**/libQnnHtpV69Stub.so"
            excludes += "**/libQnnHtpV69Skel.so"
            excludes += "**/libQnnHtpV73Stub.so"
            excludes += "**/libQnnHtpV73Skel.so"
            excludes += "**/libQnnHtpV75Stub.so"
            excludes += "**/libQnnHtpV75Skel.so"
            excludes += "**/libQnnHtpV81Stub.so"
            excludes += "**/libQnnHtpV81Skel.so"
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
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
    // Qualcomm QNN runtime for the NPU "dispatch" path (Snapdragon 8 Elite / SM8750 → Hexagon v79).
    // Public Maven Central artifact that bundles the HTP backend .so files — no QAIRT SDK login needed.
    // Pinned to the QAIRT version the per-SoC gemma-4-E2B model was built against (2.44.0). The bridge
    // libLiteRtDispatch_Qualcomm.so is fetched at build time by fetchNpuDispatchLib (below) — not in git.
    // Packaging below keeps only the v79 libs out of the ~280 MB the AAR carries for all Hexagon archs.
    implementation("com.qualcomm.qti:qnn-runtime:2.44.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
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

// --- NPU dispatch bridge: fetched at build time, NOT committed ---
// libLiteRtDispatch_Qualcomm.so (Apache-2.0) is the LiteRT→QNN bridge our Backend.NPU path dlopens.
// Google publishes it only inside LiteRT's NPU release zip (not on Maven), so this task downloads it
// into jniLibs (gitignored) before the build. It runs once and is skipped when the file already exists
// (survives `gradlew clean`; re-fetched after `git clean -x`). Network is needed only on the first build.
val npuDispatchSo = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so")
val fetchNpuDispatchLib by tasks.registering {
    description = "Download the LiteRT Qualcomm NPU dispatch bridge (not vendored in git)."
    val out = npuDispatchSo.asFile
    outputs.file(out)
    onlyIf { !out.exists() }
    doLast {
        val zipUrl =
            "https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.5/litert_npu_runtime_libraries.zip"
        // v79 = Hexagon arch for the Snapdragon 8 Elite / SM8750 (the bridge is identical across archs).
        val entry = "qualcomm_runtime_v79/src/main/jni/arm64-v8a/libLiteRtDispatch_Qualcomm.so"
        out.parentFile.mkdirs()
        val tmpZip = File.createTempFile("litert_npu", ".zip")
        fun open(u: String) = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 120_000; instanceFollowRedirects = true
        }
        try {
            logger.lifecycle("Fetching NPU dispatch bridge: $zipUrl")
            var conn = open(zipUrl)
            var hops = 0
            // GitHub release downloads redirect to objects.githubusercontent.com; follow manually too.
            while (conn.responseCode in 300..399 && hops++ < 5) {
                val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
                conn.disconnect(); conn = open(loc)
            }
            check(conn.responseCode == 200) { "download failed: HTTP ${conn.responseCode} for $zipUrl" }
            conn.inputStream.use { i -> tmpZip.outputStream().use { o -> i.copyTo(o) } }
            ZipFile(tmpZip).use { zip ->
                val ze = zip.getEntry(entry) ?: error("entry not found in zip: $entry")
                zip.getInputStream(ze).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
            logger.lifecycle("NPU dispatch bridge -> $out (${out.length()} bytes)")
        } finally {
            tmpZip.delete()
        }
    }
}
tasks.named("preBuild").configure { dependsOn(fetchNpuDispatchLib) }
