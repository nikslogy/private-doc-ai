pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack may supply only this reviewed, byte-pinned standard OCR AAR.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://jitpack.io") }
            }
            filter {
                includeModule("com.github.adaptech-cz.Tesseract4Android", "tesseract4android")
            }
        }
    }
}
rootProject.name = "PrivateDocumentAssistant"
include(":app", ":core", ":platform")
