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

dependencies {
    implementation(project(":core"))
    compileOnly(archiPlugins)
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
    doFirst { file(dropins).listFiles { f -> f.name.startsWith(bundleId) }?.forEach { it.delete() } }
    from(tasks.jar)
    into(dropins)
}
