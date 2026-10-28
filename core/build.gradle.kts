// Pure Java engine (no Eclipse/SWT): model split/merge and git sync.
// JGit version matches the bundle shipped with the Archi line we build for (see archiTarget in the root build).
val jgitVersion = rootProject.extra["jgitVersion"] as String

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17") // quiet JGit logging in the CLI; Archi provides its own
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("archiHome", providers.gradleProperty("archiHome").getOrElse(""))
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Fat jar for the command line tool (used for scripting, CI and tests without Archi).
tasks.register<Jar>("cliJar") {
    archiveFileName.set("cooperativearchi-cli.jar")
    manifest { attributes("Main-Class" to "io.github.p3chys.cooperativearchi.cli.Main") }
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
