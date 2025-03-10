plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = AndroidConfig.multisrcNamespace

    defaultConfig {
        minSdk = 29
    }

    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

configurations {
    compileOnly {
        isCanBeResolved = true
    }
}

dependencies {
    compileOnly(libs.bundles.common)

    // Implements all shared-extractors on the extensions generator
    // Note that this does not mean that generated sources are going to
    // implement them too; this is just to be able to compile and generate sources.
    rootProject.subprojects
        .filter { it.path.startsWith(":lib") }
        .forEach(::implementation)
}

tasks {
    register("generateExtensions") {
        doLast {
            val isWindows = System.getProperty("os.name").toString().lowercase().contains("win")
            var classPath = (
                configurations.compileOnly.get().asFileTree.toList() +
                    listOf(
                        configurations.androidApis.get().asFileTree.first().absolutePath, // android.jar path
                        "$projectDir/build/intermediates/aar_main_jar/debug/classes.jar", // jar made from this module
                    )
                )
                .joinToString(if (isWindows) ";" else ":")

            var javaPath = "${System.getProperty("java.home")}/bin/java"

            val mainClass = "generator.GeneratorMainKt" // Main class we want to execute

            if (isWindows) {
                classPath = classPath.replace("/", "\\")
                javaPath = javaPath.replace("/", "\\")
            }

            val javaProcess = ProcessBuilder()
                .directory(null).command(javaPath, "-classpath", classPath, mainClass)
                .redirectErrorStream(true).start()

            javaProcess.inputStream
                .bufferedReader()
                .forEachLine(logger::info)

            val exitCode = javaProcess.waitFor()
            if (exitCode != 0) {
                throw Exception("Java process failed with exit code: $exitCode")
            }
        }
        dependsOn("ktFormat", "ktLint", "assembleDebug")
    }

    register<org.jmailen.gradle.kotlinter.tasks.LintTask>("ktLint") {
        if (project.hasProperty("theme")) {
            val theme = project.property("theme")
            source(files("src/main/java/eu/kanade/tachiyomi/multisrc/$theme", "overrides/$theme"))
            return@register
        }
        source(files("src", "overrides"))
    }

    register<org.jmailen.gradle.kotlinter.tasks.FormatTask>("ktFormat") {
        if (project.hasProperty("theme")) {
            val theme = project.property("theme")
            source(files("src/main/java/eu/kanade/tachiyomi/multisrc/$theme", "overrides/$theme"))
            return@register
        }
        source(files("src", "overrides"))
    }
}
