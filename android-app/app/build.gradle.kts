plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// A stable signing identity is required for in-place APK updates. The keystore is supplied by CI
// secrets (never committed); when it is absent (local builds, pull requests) the debug key is used.
val keystoreFile = System.getenv("AVNIVO_KEYSTORE_FILE")
// Monotonic versionCode: CI overrides it per release, otherwise it defaults to the base value.
val buildVersionCode = System.getenv("AVNIVO_VERSION_CODE")?.toIntOrNull() ?: 1
// whisper.cpp is fetched by CI into src/main/cpp/whisper.cpp; when absent the native Whisper
// engine is simply not compiled, so local builds without the source keep working.
val whisperNativeSource = file("src/main/cpp/whisper.cpp")
val buildWhisperNative = whisperNativeSource.isDirectory

android {
 namespace = "com.avnivo.translator"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.avnivo.translator"
  minSdk = 26
  targetSdk = 35
  versionCode = buildVersionCode
  versionName = "1.0.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  if (buildWhisperNative) {
   ndkVersion = "27.0.12077973"
   System.getenv("AVNIVO_ABIS")?.split(",")?.filter { it.isNotBlank() }?.let { ndk { abiFilters += it } }
   externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DWHISPER_BUILD_TESTS=OFF", "-DWHISPER_BUILD_EXAMPLES=OFF", "-DWHISPER_BUILD_SERVER=OFF") } }
  }
 }
 if (buildWhisperNative) {
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
 }
 signingConfigs {
  if (keystoreFile != null) {
   create("release") {
    storeFile = file(keystoreFile)
    storePassword = System.getenv("AVNIVO_KEYSTORE_PASSWORD")
    keyAlias = System.getenv("AVNIVO_KEY_ALIAS")
    keyPassword = System.getenv("AVNIVO_KEY_PASSWORD")
   }
  }
 }
 buildTypes {
  release {
   isMinifyEnabled = false
   if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging {
  // AGP stores .so uncompressed by default. That is fine for a small native surface but not here:
  // the four ABIs of prebuilt inference runtimes are 363 MB stored versus 116 MB deflated, measured
  // on the built APK. Compressing them brings the universal APK back to roughly 120 MB. The
  // libraries are extracted on install, which costs disk but nothing at runtime.
  jniLibs { useLegacyPackaging = true }
 }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation(files("libs/sherpa-onnx-2.0.1.aar"))
 // Silero TTS runs a TorchScript Lite graph. Pinned to 2.1.0 because that is the only published
 // version whose interpreter reads bytecode v8, which is what the exported .ptl carries.
 implementation("org.pytorch:pytorch_android_lite:2.1.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
