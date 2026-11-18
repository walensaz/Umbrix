plugins {
    scala
    `java-library`
}

dependencies {
    implementation("org.scala-lang:scala3-library_3:3.3.8")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.scalameta:munit_3:1.0.4")
}

tasks.named<Test>("test") {
    filter {
        // Exclude 500MB SIMD large file benchmark from default test runs
        excludeTestsMatching("umbrix.simd.SimdAlignmentBenchmarkTest")
    }
}

tasks.register<Test>("benchmarkSimd") {
    description = "Runs on-demand SIMD hardware alignment benchmarks on Thunderbird dataset"
    group = "verification"
    useJUnitPlatform()
    filter {
        includeTestsMatching("umbrix.simd.SimdAlignmentBenchmarkTest")
    }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xmx2g"
    )
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

