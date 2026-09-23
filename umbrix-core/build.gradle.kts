plugins {
    scala
    `java-library`
}

dependencies {
    api(project(":umbrix-vfs"))
    api(project(":umbrix-simd"))
    api(project(":umbrix-index"))

    implementation("org.scala-lang:scala3-library_3:3.3.8")
    api("com.google.re2j:re2j:1.8")
    api("org.roaringbitmap:RoaringBitmap:1.3.0")
    api("it.unimi.dsi:fastutil:8.5.15")
    api("org.typelevel:cats-effect_3:3.5.7")
    api("co.fs2:fs2-core_3:3.11.0")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.scalameta:munit_3:1.0.4")
}
