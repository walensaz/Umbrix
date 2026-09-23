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
