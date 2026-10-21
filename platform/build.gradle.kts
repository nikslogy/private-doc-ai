plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.privateassistant.platform"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    flavorDimensions += "network"
    productFlavors {
        create("offline") { dimension = "network" }
        create("connected") { dimension = "network" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("androidTest").assets.srcDirs(
        rootProject.file("test-fixtures/generated"),
        rootProject.file("config"),
    )
}

// The Room plugin isolates KSP output per variant, then verifies/copies equal schemas.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlite)
    implementation(libs.sqlcipher)
    implementation(libs.tink)
    implementation(libs.pdfium)
    implementation(libs.litertlm)
    implementation(libs.exifinterface)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // ML Kit's diagnostics behavior is unresolved. It is a synthetic-data device probe only.
    androidTestImplementation(libs.mlkit.text.latin)
    androidTestImplementation(libs.mlkit.text.devanagari)
}
