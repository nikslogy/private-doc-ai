plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.privateassistant.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.privateassistant.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "network"
    productFlavors {
        create("offline") { dimension = "network" }
        create("connected") {
            dimension = "network"
            applicationIdSuffix = ".connected"
            versionNameSuffix = "-connected"
        }
    }
    buildFeatures { compose = true }
    buildTypes {
        getByName("debug") {
            // Separate from the first ephemeral-signed APKs; no uninstall/data reset needed.
            applicationIdSuffix = ".dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.biometric)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

tasks.register("verifyProductionDependencies") {
    group = "verification"
    description = "Inventory production runtime dependencies and reject the unresolved ML Kit SDK."
    doLast {
        val inventory = mutableListOf<String>()
        for (variant in listOf("offlineDebug", "connectedDebug")) {
            val modules = configurations.getByName("${variant}RuntimeClasspath")
                .incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .sortedBy { "${it.group}:${it.module}:${it.version}" }
            check(modules.none {
                it.group.startsWith("com.google.mlkit") ||
                    (it.group == "com.google.android.gms" && it.module.startsWith("play-services-mlkit"))
            }) { "ML Kit diagnostics review is unresolved; it must not be a production dependency ($variant)." }
            inventory += "[$variant]"
            inventory += modules.map { "${it.group}:${it.module}:${it.version}" }
        }
        val report = layout.buildDirectory.file("reports/dependencies/production-runtime-components.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(inventory.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Production runtime dependency inventory: ${report.absolutePath}")
    }
}
