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
        // Explicit preparation can supply these exact bytes without Gradle's
        // remote HEAD requests. The platform still verifies the resolved AAR.
        val verifiedOcrRuntime = maven {
            name = "verifiedOcrRuntime"
            url = uri(rootDir.resolve(".tooling/verified-ocr-maven"))
            metadataSources { artifact() }
        }
        val pinnedOcrOrigin = maven {
            name = "pinnedOcrOrigin"
            url = uri("https://jitpack.io")
            // The published POM path is unreliable; consume only the pinned AAR.
            metadataSources { artifact() }
        }
        // These repositories supply only this reviewed, byte-pinned OCR version.
        exclusiveContent {
            forRepositories(verifiedOcrRuntime, pinnedOcrOrigin)
            filter {
                includeVersion("com.github.adaptech-cz.Tesseract4Android", "tesseract4android", "4.9.0")
            }
        }
    }
}
rootProject.name = "PrivateDocumentAssistant"
include(":app", ":core", ":platform")
