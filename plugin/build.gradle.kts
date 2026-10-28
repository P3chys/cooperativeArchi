import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Archi plugin (OSGi bundle). Compiled against the jars of a local Archi installation (-ParchiHome),
// and packaged as a .archiplugin file that analysts install via Archi > Help > Manage Plug-ins.
val archiHome = providers.gradleProperty("archiHome").get()
val bundleId = "io.github.p3chys.cooperativearchi"
val qualifier = providers.gradleProperty("qualifier").getOrElse("v" + LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")))
val bundleVersion = "${project.version}.$qualifier"
val archiTarget = rootProject.extra["archiTarget"] as String
val manifestValues = mapOf(
    "version" to bundleVersion,
    "javaRelease" to rootProject.extra["javaRelease"] as String,
    "jgitRange" to rootProject.extra["jgitRange"] as String,
)

val archiPlugins = fileTree(archiHome) {
    include("plugins/*.jar", "plugins/*/*.jar", "Contents/Eclipse/plugins/*.jar", "Contents/Eclipse/plugins/*/*.jar")
}

// Archi's model bundle is plain EMF: enough to load, change and save models headlessly in unit tests.
val archiModelJars = fileTree(archiHome) {
    for (dir in listOf("plugins", "Contents/Eclipse/plugins")) {
        include("$dir/com.archimatetool.model_*/*.jar", "$dir/org.eclipse.emf.common_*.jar",
            "$dir/org.eclipse.emf.ecore_*.jar", "$dir/org.eclipse.emf.ecore.xmi_*.jar", "$dir/org.eclipse.osgi_*.jar")
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(archiPlugins)
    testImplementation(archiModelJars)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val core = project(":core")

tasks.jar {
    archiveFileName.set("${bundleId}_$bundleVersion.jar")
    from(core.extensions.getByType<SourceSetContainer>()["main"].output)
    from(projectDir) { include("plugin.xml", "icons/**") }
    manifest {
        from("META-INF/MANIFEST.MF") {
            eachEntry {
                value = manifestValues.entries.fold(value.toString()) { v, (k, x) -> v.replace("\${$k}", x) }
            }
        }
    }
    // the manifest's ${...} placeholders must be expanded; keep the jar reproducible otherwise
    inputs.properties(manifestValues)
}

// The format Archi's "Manage Plug-ins > Install" understands: a zip with an "archi-plugin" marker entry.
val archiplugin = tasks.register<Zip>("archiplugin") {
    group = "distribution"
    description = "Builds the installable cooperativeArchi .archiplugin file"
    archiveFileName.set("cooperativeArchi-${project.version}-archi$archiTarget.archiplugin")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("dist"))
    from(tasks.jar)
    from("archi-plugin") // empty marker entry
}

tasks.assemble { dependsOn(archiplugin) }

// Developer shortcut: copy the bundle straight into Archi's dropins folder (restart Archi afterwards).
tasks.register<Copy>("installDev") {
    group = "distribution"
    description = "Installs the plugin into the local Archi dropins folder"
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val dropins = if (os.contains("mac")) "$home/Library/Application Support/Archi/dropins"
        else if (os.contains("win")) "$home/AppData/Roaming/Archi/dropins" else "$home/.archi/dropins"
    doFirst {
        // Archi picks up a changed dropins folder only at start, and clears its configuration on a normal exit
        val running = ProcessHandle.allProcesses().anyMatch { p ->
            p.info().command().map { it.lowercase().replace('\\', '/').let { c -> c.endsWith("/archi.exe") || c.endsWith("/archi") } }
                .orElse(false)
        }
        if (running) throw GradleException("Archi is running - close it (all instances) and run this again")
        // Store apps (e.g. PowerShell 7 from the Microsoft Store) run in a sandbox that redirects writes to %APPDATA%
        // to a private copy: the plug-in would land where only programs started from that shell can see it
        val sandboxed = generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .mapNotNull { it.info().command().orElse(null) }
            .firstOrNull { it.contains("\\WindowsApps\\", ignoreCase = true) }
            // the Gradle daemon may have no live parents; the calling shell's environment still shows a Store pwsh
            ?: System.getenv("PSModulePath")?.split(';')?.firstOrNull { it.contains("\\WindowsApps\\", ignoreCase = true) }
        if (sandboxed != null) throw GradleException("Started from a Microsoft Store app ($sandboxed), whose sandbox " +
            "hides %APPDATA% changes from Archi. Run it from Git Bash, cmd, Windows PowerShell, or a PowerShell 7 " +
            "installed with winget/MSI.")
        // an old copy left behind keeps being loaded by Archi, so a file that cannot be removed is an error
        file(dropins).listFiles { f -> f.name.startsWith(bundleId) }?.forEach {
            if (!it.delete()) throw GradleException("Cannot remove $it - close Archi (all instances) and run it again")
        }
        // Archi only reinstalls dropins it believes changed. Its notes can outlive the installed list (e.g. after its
        // configuration was cleared under a running Archi), and then the plug-in silently stays out: forget them, for
        // this Archi and the second instances of `just second-archi` (Archi-<name>)
        val data = file(dropins).parentFile
        (listOf(data) + data.parentFile.listFiles { f -> f.isDirectory && f.name.startsWith(data.name + "-") }.orEmpty())
            .map { it.resolve("config/org.eclipse.osgi") }.filter { it.isDirectory }
            .forEach { osgi ->
                osgi.walkTopDown().filter { it.isFile && (it.name == "cache.timestamps" || it.name.startsWith("timestamps")) }
                    .forEach { it.delete() }
            }
    }
    from(tasks.jar)
    into(dropins)
}
