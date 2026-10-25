plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stableDevelopment = providers.gradleProperty("stableDevelopment").orNull.let {
    check(it == null || it == "true" || it == "false") { "Invalid stableDevelopment property" }
    it == "true"
}

android {
    namespace = "com.privateassistant.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.privateassistant.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.2-dev"
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
    if (stableDevelopment) {
        signingConfigs.create("stableDevelopment") {
            val path = providers.environmentVariable("ANDROID_DEV_KEYSTORE_PATH").orNull
            val password = providers.environmentVariable("ANDROID_DEV_STORE_PASSWORD").orNull
            check(!path.isNullOrBlank() && !password.isNullOrBlank()) { "Stable development signing credentials are required" }
            storeFile = file(requireNotNull(path))
            check(requireNotNull(storeFile).isFile) { "Stable development keystore is absent" }
            storeType = "PKCS12"
            storePassword = password
            keyAlias = "private-assistant-dev"
            keyPassword = password
        }
    }
    buildTypes {
        getByName("debug") {
            // Separate from the first ephemeral-signed APKs; no uninstall/data reset needed.
            applicationIdSuffix = if (stableDevelopment) ".preview" else ".dev"
            if (stableDevelopment) signingConfig = signingConfigs.getByName("stableDevelopment")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

if (stableDevelopment) {
    val labels = layout.buildDirectory.dir("generated/stableLabels")
    for (flavor in listOf("offline", "connected")) {
        android.sourceSets.maybeCreate("${flavor}Debug").res.setSrcDirs(listOf(labels.map { it.dir(flavor) }))
    }
    val generateStableLabels by tasks.registering {
        outputs.dir(labels)
        doLast {
            for (flavor in listOf("offline", "connected")) {
                val output = labels.get().file("$flavor/values/strings.xml").asFile
                output.parentFile.mkdirs()
                val name = if (flavor == "offline") "Private Vault Preview" else "Private Vault Connected Preview"
                output.writeText("<resources><string name=\"app_name\">$name</string></resources>\n")
            }
        }
    }
    tasks.named("preBuild").configure { dependsOn(generateStableLabels) }
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
