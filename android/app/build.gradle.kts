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
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/avnivoAssets"))
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
val prepareAvnivoAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html", "offline-uzbek-download.js", "offline-module-manager.js")
        include("i18n/**")
    }
    into(layout.buildDirectory.dir("generated/avnivoAssets"))
}
tasks.named("preBuild") { dependsOn(prepareAvnivoAssets) }
