plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// The direct AAR has no transitive dependency or POM-driven behavior. The exact
// same coordinate is verified below before Android preBuild can complete.
val tesseractAarCoordinate = "com.github.adaptech-cz.Tesseract4Android:tesseract4android:${libs.versions.tesseract.get()}@aar"
val tesseractBinary by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
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
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ocrAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/modelCatalogue"))
    sourceSets.getByName("androidTest").assets.srcDirs(
        rootProject.file("test-fixtures/generated"),
        rootProject.file("config"),
    )
}

// Downloads are explicit in check-host.sh. Direct Gradle builds verify the exact
// generated public assets without network access or implicit model selection.
val verifyOcrAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare-ocr-assets.py", "--verify-only")
    inputs.file(rootProject.file("config/ocr-models.json"))
    inputs.file(rootProject.file("scripts/prepare-ocr-assets.py"))
    inputs.dir(layout.buildDirectory.dir("generated/ocrAssets"))
}
val verifyTesseractBinary by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.file(rootProject.file("config/ocr-runtime.json"))
    inputs.file(rootProject.file("scripts/verify-ocr-dependency.py"))
    inputs.files(tesseractBinary)
    doFirst {
        commandLine("python3", "scripts/verify-ocr-dependency.py", tesseractBinary.singleFile.absolutePath)
    }
}
val copyModelCatalogue by tasks.registering(Copy::class) {
    from(rootProject.file("config/models.json"))
    into(layout.buildDirectory.dir("generated/modelCatalogue/models"))
    rename { "catalogue.json" }
}
tasks.named("preBuild").configure { dependsOn(verifyOcrAssets, verifyTesseractBinary, copyModelCatalogue) }

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
    implementation(tesseractAarCoordinate) { isTransitive = false }
    add(tesseractBinary.name, tesseractAarCoordinate)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // ML Kit's diagnostics behavior is unresolved. It is a synthetic-data device probe only.
    androidTestImplementation(libs.mlkit.text.latin)
    androidTestImplementation(libs.mlkit.text.devanagari)
}
