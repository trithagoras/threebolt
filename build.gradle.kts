plugins {
    java
    antlr
    application
}

group = "dev.threebolt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.generateGrammarSource {
    val pkgDir = outputDirectory.resolve("dev/threebolt/parser")
    // -lib lets the split parser grammar find ThreeboltLexer.tokens (tokenVocab).
    arguments = arguments + listOf(
        "-visitor", "-no-listener",
        "-package", "dev.threebolt.parser",
        "-lib", pkgDir.absolutePath,
    )
    doFirst { pkgDir.mkdirs() }
}

application {
    mainClass = "dev.threebolt.Main"
    applicationName = "threebolt"
    // FFM (C interop) needs native access.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    // `./gradlew test -PupdateGolden` rewrites golden files instead of comparing against them.
    systemProperty("threebolt.updateGolden", providers.gradleProperty("updateGolden").isPresent)
    inputs.dir("examples")
    inputs.file("docs/spec/threebolt-1.0.md")
}
