plugins {
    id("com.android.application") version "9.4.0"
}

android {
    namespace = "com.miokzz.rawbench"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miokzz.rawbench"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "lab"
        ndk.abiFilters += "arm64-v8a"
    }
    packaging.jniLibs.useLegacyPackaging = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    val localQnn = providers.gradleProperty("rawQnnDir").orNull
    if (localQnn == null) {
        implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
        implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")
    } else {
        implementation(files("$localQnn/qnn-runtime.aar", "$localQnn/qnn-litert-delegate.aar"))
    }
    testImplementation("junit:junit:4.13.2")
}
