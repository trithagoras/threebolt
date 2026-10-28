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
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("com.electronwill.night-config:toml:3.9.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The antlr plugin puts the whole ANTLR tool on the runtime classpath; only the runtime is needed.
configurations.api {
    setExtendsFrom(extendsFrom.filter { it.name != "antlr" })
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

// Programs read stdin (`readln`); Gradle doesn't forward it by default.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    inputs.dir("tests/conformance")
    // `./gradlew test -PupdateGolden` rewrites golden files instead of comparing against them.
    systemProperty("threebolt.updateGolden", providers.gradleProperty("updateGolden").isPresent)
    inputs.dir("examples")
    inputs.file("docs/spec/threebolt-1.0.md")
}

// One jar with every dependency, for `threebolt lsp` launched by the VS Code extension.
val serverJar by tasks.registering(Jar::class) {
    archiveFileName = "threebolt.jar"
    destinationDirectory = layout.buildDirectory.dir("server")
    manifest { attributes("Main-Class" to "dev.threebolt.Main", "Enable-Native-Access" to "ALL-UNNAMED") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Puts the server jar into the extension (editors/vscode/server/), ready to package.
tasks.register<Copy>("vscodeServer") {
    from(serverJar)
    into("editors/vscode/server")
}

// `./gradlew bench [-Pbench=name]`: runs tests/bench/*.3b with the installed CLI and prints each one's
// wall-clock time and output (see tests/bench/README.md).
tasks.register("bench") {
    dependsOn(tasks.installDist)
    val only = providers.gradleProperty("bench")
    val cli = layout.buildDirectory.file("install/threebolt/bin/threebolt")
    val dir = layout.projectDirectory.dir("tests/bench")
    doLast {
        val files = dir.asFile.listFiles { f -> f.extension == "3b" }!!.sortedBy { it.name }
            .filter { !only.isPresent || it.nameWithoutExtension == only.get() }
        for (f in files) {
            val start = System.nanoTime()
            val proc = ProcessBuilder(cli.get().asFile.path, "run", f.path).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            val ms = (System.nanoTime() - start) / 1_000_000
            println("%-12s %7d ms  %s".format(f.nameWithoutExtension, ms, if (code == 0) out else "exit $code: $out"))
        }
    }
}
