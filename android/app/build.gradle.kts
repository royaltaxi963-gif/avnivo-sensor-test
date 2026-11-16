plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.avnivo.translator"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.avnivo.translator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Internal testing only: signed with the debug key so a release APK can
            // be produced without embedding a private key in the repository.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // WebViewAssetLoader: serve bundled assets from a trusted https origin.
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
}

// The WebView UI is the single source of truth at the repository root. Copy the
// web app (index.html, its scripts, and the 100 language packages) into the app
// assets at build time so the bundled UI cannot drift from the web app, and so
// no large generated assets are duplicated in version control.
val repoRoot = layout.projectDirectory.dir("../..")
val webAssetsDir = layout.buildDirectory.dir("web-assets")

val syncWebAssets = tasks.register<Sync>("syncWebAssets") {
    from(repoRoot.file("index.html"))
    from(repoRoot.file("offline-uzbek-download.js"))
    from(repoRoot.dir("i18n")) {
        include("avnivo-i18n.js", "languages.json", "locales/*.json")
        into("i18n")
    }
    into(webAssetsDir)
}

android.sourceSets["main"].assets.srcDir(webAssetsDir)
tasks.named("preBuild") { dependsOn(syncWebAssets) }
