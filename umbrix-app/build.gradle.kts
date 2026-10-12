plugins {
    scala
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.gradleup.shadow")
    application
}

application {
    mainClass.set("umbrix.app.Main")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xmx4g"
    )
}

dependencies {
    implementation(project(":umbrix-ui"))
    implementation(project(":umbrix-mcp"))
    implementation(project(":umbrix-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    implementation("org.scala-lang:scala3-library_3:3.3.8")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("ch.qos.logback:logback-core:1.5.16")

    testImplementation("org.scalameta:munit_3:1.0.4")
}

// Item 6.3: Production Fat JAR packaging configuration
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("umbrix")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()

    // Filter signature files from upstream dependencies to prevent SecurityException
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    manifest {
        attributes(
            "Main-Class" to "umbrix.app.Main",
            "Multi-Release" to "true",
            "Implementation-Title" to "Umbrix Workstation",
            "Implementation-Version" to project.version.toString()
        )
    }
}

val javaToolchainService = project.extensions.getByType<JavaToolchainService>()
val jdk25Launcher = javaToolchainService.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val createMinRuntime by tasks.registering {
    group = "distribution"
    description = "Creates a stripped minimal JDK 25 runtime image via jlink containing only required modules."

    val outputDir = layout.buildDirectory.dir("min-runtime").get().asFile
    outputs.dir(outputDir)

    doLast {
        val jdkHome = jdk25Launcher.get().metadata.installationPath.asFile
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val jlinkBin = File(jdkHome, if (isWindows) "bin/jlink.exe" else "bin/jlink")

        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }

        val modules = mutableListOf(
            "java.base",
            "java.desktop",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "jdk.httpserver",
            "jdk.unsupported",
            "jdk.incubator.vector",
            "jdk.charsets",
            "jdk.crypto.ec"
        )
        if (isWindows) {
            modules.add("jdk.crypto.mscapi")
        }

        println("Building stripped Min-JDK 25 runtime image at: ${outputDir.absolutePath}")
        project.exec {
            commandLine(
                jlinkBin.absolutePath,
                "--add-modules", modules.joinToString(","),
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress", "zip-6",
                "--output", outputDir.absolutePath
            )
        }
        println("Min-JDK 25 runtime image created successfully.")
    }
}

val createAppImage by tasks.registering {
    group = "distribution"
    description = "Packages a standalone native app-image via jpackage using the stripped min-runtime."
    dependsOn("shadowJar", createMinRuntime)

    val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    inputs.file(shadowJarTask.flatMap { it.archiveFile })
    inputs.dir(layout.buildDirectory.dir("min-runtime"))

    val distDir = layout.buildDirectory.dir("dist").get().asFile
    val outputAppImage = File(distDir, "Umbrix")
    outputs.dir(outputAppImage)

    doLast {
        val jdkHome = jdk25Launcher.get().metadata.installationPath.asFile
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
        val jpackageBin = File(jdkHome, if (isWindows) "bin/jpackage.exe" else "bin/jpackage")
        val minRuntimeDir = layout.buildDirectory.dir("min-runtime").get().asFile
        val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
        val shadowJarFile = shadowJarTask.archiveFile.get().asFile
        val iconFile = if (isWindows) {
            project(":umbrix-ui").file("src/main/resources/icons/umbrix.ico")
        } else if (isMac) {
            project(":umbrix-ui").file("src/main/resources/icons/umbrix.icns")
        } else {
            project(":umbrix-ui").file("src/main/resources/icons/umbrix_icon.png")
        }

        if (outputAppImage.exists()) {
            outputAppImage.deleteRecursively()
        }
        distDir.mkdirs()

        // Create isolated input folder containing only the shadow jar
        val jpackageInput = layout.buildDirectory.dir("jpackage-input").get().asFile
        if (jpackageInput.exists()) jpackageInput.deleteRecursively()
        jpackageInput.mkdirs()
        shadowJarFile.copyTo(File(jpackageInput, shadowJarFile.name), overwrite = true)

        val jpackageArgs = mutableListOf(
            jpackageBin.absolutePath,
            "--type", "app-image",
            "--name", "Umbrix",
            "--app-version", "1.0.0",
            "--vendor", "Umbrix",
            "--input", jpackageInput.absolutePath,
            "--main-jar", shadowJarFile.name,
            "--main-class", "umbrix.app.Main",
            "--runtime-image", minRuntimeDir.absolutePath,
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "--add-modules=jdk.incubator.vector",
            "--java-options", "-Xmx4g",
            "--dest", distDir.absolutePath
        )

        if (iconFile.exists()) {
            jpackageArgs.addAll(listOf("--icon", iconFile.absolutePath))
        }

        println("Running jpackage to create native app-image...")
        project.exec {
            commandLine(jpackageArgs)
        }
        println("Native app-image created at: ${outputAppImage.absolutePath}")

        // Copy Windows context menu setup scripts into app-image if on Windows
        if (isWindows) {
            val scriptsDir = rootProject.file("distribution/windows")
            File(scriptsDir, "register-open-with.bat").let {
                if (it.exists()) it.copyTo(File(outputAppImage, "register-open-with.bat"), overwrite = true)
            }
            File(scriptsDir, "unregister-open-with.bat").let {
                if (it.exists()) it.copyTo(File(outputAppImage, "unregister-open-with.bat"), overwrite = true)
            }
            println("Copied Windows Explorer context menu scripts into app-image.")
        }
    }
}

val packagePortableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the standalone native app-image into a portable zero-install ZIP archive."
    dependsOn(createAppImage)

    val distDir = layout.buildDirectory.dir("dist").get().asFile
    val osName = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "windows-x64"
                 else if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) "macos"
                 else "linux-x64"

    archiveBaseName.set("Umbrix")
    archiveVersion.set("1.0.0")
    archiveClassifier.set("$osName-portable")
    destinationDirectory.set(distDir)

    from(File(distDir, "Umbrix")) {
        into("Umbrix")
    }
}

val packageDmg by tasks.registering {
    group = "distribution"
    description = "Packages a standalone native macOS .dmg disk image via jpackage (runs on macOS)."
    dependsOn("shadowJar", createMinRuntime)

    val distDir = layout.buildDirectory.dir("dist").get().asFile

    doLast {
        if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            println("Skipping packageDmg: Host is not macOS.")
            return@doLast
        }

        val jdkHome = jdk25Launcher.get().metadata.installationPath.asFile
        val jpackageBin = File(jdkHome, "bin/jpackage")
        val minRuntimeDir = layout.buildDirectory.dir("min-runtime").get().asFile
        val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
        val shadowJarFile = shadowJarTask.archiveFile.get().asFile
        val jpackageInput = layout.buildDirectory.dir("jpackage-input").get().asFile
        if (jpackageInput.exists()) jpackageInput.deleteRecursively()
        jpackageInput.mkdirs()
        shadowJarFile.copyTo(File(jpackageInput, shadowJarFile.name), overwrite = true)

        val jpackageArgs = mutableListOf(
            jpackageBin.absolutePath,
            "--type", "dmg",
            "--name", "Umbrix",
            "--app-version", "1.0.0",
            "--vendor", "Umbrix",
            "--input", jpackageInput.absolutePath,
            "--main-jar", shadowJarFile.name,
            "--main-class", "umbrix.app.Main",
            "--runtime-image", minRuntimeDir.absolutePath,
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "--add-modules=jdk.incubator.vector",
            "--java-options", "-Xmx4g",
            "--dest", distDir.absolutePath
        )

        val iconFile = project(":umbrix-ui").file("src/main/resources/icons/umbrix.icns")
        if (iconFile.exists()) {
            jpackageArgs.addAll(listOf("--icon", iconFile.absolutePath))
        }

        project.exec {
            commandLine(jpackageArgs)
        }
        println("Native macOS DMG packaged successfully in ${distDir.absolutePath}")
    }
}

val packageMsi by tasks.registering {
    group = "distribution"
    description = "Packages a Windows .msi installer via jpackage with file associations (requires WiX)."
    dependsOn("shadowJar", createMinRuntime)

    val distDir = layout.buildDirectory.dir("dist").get().asFile

    doLast {
        if (!org.gradle.internal.os.OperatingSystem.current().isWindows) {
            println("Skipping packageMsi: Host is not Windows.")
            return@doLast
        }

        val jdkHome = jdk25Launcher.get().metadata.installationPath.asFile
        val jpackageBin = File(jdkHome, "bin/jpackage.exe")
        val minRuntimeDir = layout.buildDirectory.dir("min-runtime").get().asFile
        val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
        val shadowJarFile = shadowJarTask.archiveFile.get().asFile
        val iconFile = project(":umbrix-ui").file("src/main/resources/icons/umbrix.ico")

        val jpackageInput = layout.buildDirectory.dir("jpackage-input").get().asFile
        if (jpackageInput.exists()) jpackageInput.deleteRecursively()
        jpackageInput.mkdirs()
        shadowJarFile.copyTo(File(jpackageInput, shadowJarFile.name), overwrite = true)

        val jpackageArgs = mutableListOf(
            jpackageBin.absolutePath,
            "--type", "msi",
            "--name", "Umbrix",
            "--app-version", "1.0.0",
            "--vendor", "Umbrix",
            "--input", jpackageInput.absolutePath,
            "--main-jar", shadowJarFile.name,
            "--main-class", "umbrix.app.Main",
            "--runtime-image", minRuntimeDir.absolutePath,
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "--add-modules=jdk.incubator.vector",
            "--java-options", "-Xmx4g",
            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser",
            "--dest", distDir.absolutePath
        )

        if (iconFile.exists()) {
            jpackageArgs.addAll(listOf("--icon", iconFile.absolutePath))
        }

        project.exec {
            commandLine(jpackageArgs)
        }
        println("Native Windows MSI packaged successfully in ${distDir.absolutePath}")
    }
}

