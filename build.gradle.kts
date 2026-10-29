allprojects {
    group = "io.github.p3chys.cooperativearchi"
    repositories { mavenCentral() }
}

plugins {
    // rewrites the Archi 4.9 plugin jar for Java 11 (the sources need Java 17)
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1" apply false
}

// Archi line to build for (-ParchiTarget=...): the Java level and JGit version that line bundles.
// Each target produces its own .archiplugin; one plugin version is published for every supported line.
// Archi 4.9 bundles no JGit (empty range): the plugin carries its own copy.
val archiTargets = mapOf(
    "4.9" to listOf("11", "6.10.0.202406032230-r", ""),  // Archi 4.9.x
    "5.4" to listOf("17", "6.10.0.202406032230-r", "[6.10.0,8.0.0)"),  // Archi 5.4 - 5.9
    "5.10" to listOf("21", "7.7.0.202606012155-r", "[7.0.0,8.0.0)"),  // Archi 5.10+
)
val archiTarget = providers.gradleProperty("archiTarget").get()
val (javaRelease, jgitVersion, jgitRange) = archiTargets[archiTarget]
    ?: error("Unknown archiTarget '$archiTarget', use one of ${archiTargets.keys}")
extra["archiTarget"] = archiTarget
extra["javaRelease"] = javaRelease
extra["jgitVersion"] = jgitVersion
extra["jgitRange"] = jgitRange

subprojects {
    apply(plugin = "java")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        // below Java 17 the compiled jar is downgraded afterwards (see plugin/build.gradle.kts)
        options.release.set(maxOf(javaRelease.toInt(), 17))
        options.encoding = "UTF-8"
    }
}
