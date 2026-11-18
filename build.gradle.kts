plugins {
    kotlin("jvm") version "2.1.10" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

allprojects {
    group = "umbrix"
    version = "1.0.1"
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(
                listOf(
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-modules=jdk.incubator.vector"
                )
            )
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector",
                "-Xmx2g"
            )
        }

        tasks.withType<Jar>().configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        dependencies {
            "testRuntimeOnly"("org.junit.vintage:junit-vintage-engine:5.11.4")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
        }
    }

    plugins.withId("scala") {
        tasks.withType<ScalaCompile>().configureEach {
            scalaCompileOptions.encoding = "UTF-8"
            scalaCompileOptions.additionalParameters = listOf(
                "-feature",
                "-explain"
            )
            scalaCompileOptions.forkOptions.jvmArgs = listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector"
            )
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                // Item 6.2: Align Kotlin bytecode to JVM 21 for Compose Desktop 1.7.3 & Skiko native compatibility
                // while compiling under the JDK 25 toolchain.
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                freeCompilerArgs.addAll(
                    listOf(
                        "-Xjsr305=strict"
                    )
                )
            }
        }
    }
}
