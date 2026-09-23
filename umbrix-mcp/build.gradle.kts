plugins {
    scala
    `java-library`
}

dependencies {
    api(project(":umbrix-core"))

    implementation("org.scala-lang:scala3-library_3:3.3.8")
    api("dev.langchain4j:langchain4j-core:1.20.0")
    api("com.knuddels:jtokkit:1.1.0")
    api("org.slf4j:slf4j-api:2.0.16")

    // Item 6.4: High-performance zero-allocation JSON-RPC serialization for MCP protocols
    api("com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3:2.33.2")
    compileOnly("com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-macros_3:2.33.2")
    testCompileOnly("com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-macros_3:2.33.2")

    testImplementation("org.scalameta:munit_3:1.0.4")
}
