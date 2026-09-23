plugins {
    scala
    `java-library`
}

dependencies {
    implementation("org.scala-lang:scala3-library_3:3.3.8")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.scalameta:munit_3:1.0.4")
    testImplementation("ch.qos.logback:logback-classic:1.5.16")
}
