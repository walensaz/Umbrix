plugins {
    scala
    `java-library`
}

dependencies {
    api(project(":umbrix-vfs"))
    api(project(":umbrix-simd"))
    api(project(":umbrix-index"))
    api(project(":umbrix-plugin-api"))

    implementation("org.scala-lang:scala3-library_3:3.3.8")
    api("com.google.re2j:re2j:1.8")
    api("org.roaringbitmap:RoaringBitmap:1.3.0")
    api("it.unimi.dsi:fastutil:8.5.15")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.scalameta:munit_3:1.0.4")
}

tasks.named<Test>("test") {
    filter {
        // Exclude large file benchmarks from default test runs so standard testing remains fast and headless CI passes
        excludeTestsMatching("umbrix.core.benchmark.LargeDatasetBenchmarkTest")
        excludeTestsMatching("umbrix.core.session.ProgressiveIndexingBenchmarkTest")
    }
}

tasks.register<Test>("benchmarkLargeFiles") {
    description = "Runs on-demand performance benchmarks on real multi-gigabyte log datasets (Thunderbird.log, Hadoop)"
    group = "verification"
    useJUnitPlatform()
    filter {
        includeTestsMatching("umbrix.core.benchmark.LargeDatasetBenchmarkTest")
    }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xmx4g"
    )
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

tasks.register<Test>("benchmarkProgressive") {
    description = "Runs progressive indexing benchmark on Thunderbird dataset"
    group = "verification"
    useJUnitPlatform()
    filter {
        includeTestsMatching("umbrix.core.session.ProgressiveIndexingBenchmarkTest")
    }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xmx4g"
    )
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

tasks.register<Test>("benchmarkRoaring") {
    description = "Runs 64-bit RoaringBitmap benchmark"
    group = "verification"
    useJUnitPlatform()
    filter {
        includeTestsMatching("umbrix.core.search.RoaringBitmapBenchmarkTest")
    }
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xmx4g"
    )
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
