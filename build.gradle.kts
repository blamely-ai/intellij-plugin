buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.guardsquare:proguard-gradle:7.4.2") }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import proguard.gradle.ProGuardTask

group = "ai.blamely"
version = "1.0.0"

java {
    toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17)) }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val sandboxFull = project.findProperty("blamely.sandbox.full") == "true"
/** When true (e.g. ./run-sandbox.sh --copilot), resolves GitHub Copilot into the sandbox via Marketplace API. */
val sandboxCopilot = project.findProperty("blamely.sandbox.copilot") == "true"

/** Every bundled plugin ID for the resolved IC version (see `printBundledPlugins`), excluding platform core `com.intellij`. */
val sandboxFullPluginIds: List<String> =
    project.file("sandbox-full-plugins.txt")
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    intellijPlatform {
        intellijIdeaCommunity("2023.2")
        if (sandboxFull) {
            check(sandboxFullPluginIds.isNotEmpty()) {
                "sandbox-full-plugins.txt is missing or empty; run ./gradlew printBundledPlugins to refresh IDs"
            }
            bundledPlugins(sandboxFullPluginIds)
        } else {
            bundledPlugin("Git4Idea")
        }
        if (sandboxCopilot) {
            compatiblePlugin("com.github.copilot")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    if (project.findProperty("blamely.release") == "true") {
        kotlinOptions.freeCompilerArgs += listOf(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

// Marketplace / release ZIPs should not include a sources JAR unless explicitly requested.
tasks.named<Jar>("kotlinSourcesJar") {
    enabled = project.findProperty("blamely.withSourcesJar") == "true"
}

// Do not package log files into the plugin distribution
tasks.named<Copy>("processResources") {
    exclude("**/.blamely.log", "**/blamely.log")
}

// Ensure build/distributions is populated when running "build" (e.g. from IDE)
tasks.named("build") {
    dependsOn(tasks.named("buildPlugin"))
}

/**
 * Gradle `prepareSandbox` is a [Sync]: each run replaces `plugins/` under the IDE sandbox with exactly
 * what this build declares (Blamely JAR + bundled/marketplace deps). Plugins installed manually inside
 * the sandbox IDE are removed on the next run — that is expected, not Blamely uninstalling itself.
 *
 * To keep extra plugins across runs, either declare them in `dependencies { intellijPlatform { plugin(...) } }`,
 * use `./run-sandbox.sh --copilot`, or unpack plugin folders into `sandbox-extra-plugins/` (see README there).
 */
tasks.named<Sync>("prepareSandbox").configure {
    val extraRoot = layout.projectDirectory.dir("sandbox-extra-plugins").asFile
    if (extraRoot.exists() && extraRoot.list()?.isNotEmpty() == true) {
        from(extraRoot)
    }
}

tasks.named("runIde") {
    val logDir = file("${layout.buildDirectory.get().asFile}/runIde-logs")
    (this as? org.gradle.api.tasks.JavaExec)?.jvmArgumentProviders?.add(
        org.gradle.process.CommandLineArgumentProvider {
            listOf(
                "-Didea.log.path=${logDir.absolutePath}",
                // Prefer New UI in the sandbox (Appearance → New UI when supported)
                "-Didea.experimental.ui=true"
            )
        }
    )
}

val blamelyObfuscate = project.findProperty("blamely.obfuscate") == "true"

val composedJarTask = tasks.named<Jar>("composedJar")
val compileClasspath = configurations.named("compileClasspath")

val jdkHomeForObfuscation = extensions.getByType(JavaToolchainService::class.java)
    .launcherFor(extensions.getByType(JavaPluginExtension::class.java).toolchain)
    .get()
    .metadata
    .installationPath
    .asFile

val obfuscateComposedJar = tasks.register<ProGuardTask>("obfuscateComposedJar") {
    group = "intellij platform"
    description = "Obfuscate the composed plugin JAR (enable with -Pblamely.obfuscate=true)."
    dependsOn(composedJarTask)

    onlyIf { blamelyObfuscate }

    val rules = layout.projectDirectory.file("proguard/blamely-release.pro")
    configuration(rules.asFile)

    injars(composedJarTask.get().archiveFile.get().asFile)
    outjars(layout.buildDirectory.file("tmp/blamely-obf/composed.jar").get().asFile)

    val cp = compileClasspath.get()
    val appJar = cp.files.firstOrNull { it.name == "app.jar" && it.path.contains("ideaIC") }
        ?: error("Could not locate extracted ideaIC app.jar on compile classpath (needed for ProGuard).")
    val ideRoot = appJar.parentFile.parentFile
    libraryjars(fileTree(ideRoot.resolve("lib")) { include("*.jar") })
    libraryjars(fileTree(ideRoot.resolve("plugins/vcs-git/lib")) { include("*.jar") })
    val externalJars = cp.files.filter { f -> !f.path.contains("ideaIC") && f.extension == "jar" }
    libraryjars(files(externalJars))
    val jmods = jdkHomeForObfuscation.resolve("jmods")
    if (jmods.isDirectory) {
        jmods.listFiles { f -> f.isFile && f.extension == "jmod" }?.sortedBy { it.name }?.forEach { jmod ->
            libraryjars(jmod)
        }
    }
}

tasks.register("installObfuscatedComposedJar") {
    group = "intellij platform"
    description = "Replace the composed JAR with the obfuscated output (requires -Pblamely.obfuscate=true)."
    dependsOn(obfuscateComposedJar)
    onlyIf { blamelyObfuscate }

    doLast {
        val cj = composedJarTask.get()
        val obf = layout.buildDirectory.file("tmp/blamely-obf/composed.jar").get().asFile
        val target = cj.archiveFile.get().asFile
        obf.copyTo(target, overwrite = true)
    }
}

if (blamelyObfuscate) {
    // Ensure the ZIP from buildPlugin contains the obfuscated composed JAR (not only sandbox prepareSandbox).
    tasks.named("buildPlugin") {
        dependsOn("installObfuscatedComposedJar")
    }
    tasks.named("prepareSandbox") {
        dependsOn("installObfuscatedComposedJar")
    }
}
