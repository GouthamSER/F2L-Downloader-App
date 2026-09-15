plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.f2l.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.f2l.downloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.0.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // BitTorrent (magnet links + .torrent files) — real native libtorrent bindings.
    // Tried "bt" (bt-core) first: Guice-based, hits Class.getAnnotatedSuperclass() which
    // Android's ART doesn't implement — not fixable from app code.
    // Tried com.frostwire:jlibtorrent next: same underlying library, but its distribution
    // host (dl.frostwire.com/maven) is dead — redirects to a 404 marketing page.
    // org.libtorrent4j is the same author's actively-maintained republish of the same
    // codebase, sitting directly on real Maven Central — no custom/unreliable repo needed.
    val libtorrent4jVersion = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4jVersion")
    debugImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
