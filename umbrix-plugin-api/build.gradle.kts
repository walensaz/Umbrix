plugins {
    scala
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":umbrix-vfs"))
    api(project(":umbrix-simd"))
    api(project(":umbrix-index"))

    implementation("org.scala-lang:scala3-library_3:3.3.8")
    api("org.roaringbitmap:RoaringBitmap:1.3.0")
    api("it.unimi.dsi:fastutil:8.5.15")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.scalameta:munit_3:1.0.4")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "umbrix"
            artifactId = "umbrix-plugin-api"
            version = project.version.toString()
        }
    }
}
