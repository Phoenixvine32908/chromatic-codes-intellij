plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "net.phoenix.chromatic"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        pluginVerifier()
        zipSigner()
    }
    implementation("org.yaml:snakeyaml:2.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "Chromatic Codes Preview"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
        changeNotes = """
            <ul>
                <li>Highlighting and gutter previews now work in any file type (JSON lang files, YAML, properties, Java, Kotlin).</li>
                <li>Gutter color picker writes changes back to the YAML config; gradients get a two-color editor.</li>
                <li>Generator: movement effects can now have solid, gradient or rainbow colors, and effects can be named and saved.</li>
                <li>Vanilla &sect;0-&sect;f colors built in; config file is found in config/ folders.</li>
                <li>Thread-safe registry reloads.</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            // Locally installed IDEs, so nothing large needs downloading
            local(file("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.2"))
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(17)
}
