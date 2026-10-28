# Archi version compatibility

**Oldest supported Archi version: 5.10.**

Archi 5.9 and older can't resolve the plugin. The manifest requires
`org.eclipse.jgit;bundle-version="[7.0.0,8.0.0)"`, and Archi 5.10 is the first release that ships JGit 7
(it ships 7.7). Archi 5.4–5.9 ship JGit 6.10. Archi 4.x is out of scope: it runs on Java 8 / 11.

The plugin code itself is not what limits this (see [Lowering the minimum](#lowering-the-minimum)).
If the manifest accepted JGit 6.10, Archi **5.6** would be the floor. That is the first Archi on Java 21.

## Per-version results

| Archi | Tag date | Eclipse base | Java (Archi bundle BREE / bundled JRE) | `org.eclipse.jgit` | `jface.notifications` | `equinox.security` | Plugin code compiles¹ | Bundle resolves (current manifest)² |
|-------|----------|--------------|------------------------------|--------------------|-----------------------|--------------------|-----------------------|---------------------------|
| 5.10.0 | 2026-09 | 2026-06 (4.40) | 21 / 21 | 7.7.0 | yes | yes | yes | **yes** |
| 5.9.0  | 2026-04 | 2024-06 (4.32) | 21 / 21 | 6.10.0 | yes (0.7.200) | yes | yes | no: JGit < 7.0.0 |
| 5.8.0  | 2026-02 | 2024-06 (4.32) | 21 / 21 | 6.10.0 | yes | yes | yes | no: JGit < 7.0.0 |
| 5.7.0  | 2025-09 | 2024-06 (4.32) | 21 / 21 | 6.10.0 | yes | yes | yes | no: JGit < 7.0.0 |
| 5.6.0  | 2025-05 | 2024-06 (4.32) | 21 / 21 | 6.10.0 | yes | yes | yes | no: JGit < 7.0.0 |
| 5.5.0  | 2025-01 | 2024-06 (4.32) | 17 / 17³ | 6.10.0 | yes | yes | yes (Archi API) | no: JavaSE-21 and JGit < 7.0.0 |
| 5.4.x  | 2024-08 | 2024-06 (4.32) | 17 / 17³ | 6.10.0 | yes | yes | yes (Archi API) | no: JavaSE-21 and JGit < 7.0.0 |
| 5.2–5.3 | 2023-10 – 2024-03 | 2023-06 (4.28) | 17 / 17³ | 6.6.0 | yes (0.6.0) | yes | not checked per tag⁴ | no: JavaSE-21 and JGit < 7.0.0 |
| 5.1.0  | 2023-08 | 2023-06 (4.28) | 17 / 17³ | **not shipped** | yes (0.6.0) | yes | not checked per tag⁴ | no: JavaSE-21, no JGit bundle |
| 5.0.x  | 2023-03 | 2022-09 (4.25) | 17 / 17³ | **not shipped** | yes (0.5.0) | yes | yes (Archi API) | no: JavaSE-21, no JGit bundle |

¹ Checked against the Archi source at each release tag and the JGit version that release ships. See [How this was determined](#how-this-was-determined).
² Inferred from the bundle metadata. Not checked in a running Archi (see below).
³ Archi's own bundles declare `JavaSE-17` up to 5.5 and `JavaSE-21` from 5.6 (commit "Java 21", 2025-03-09, after the 5.5.0 tag).
The bundled JRE could not be read directly. It is inferred from that.
⁴ The Archi API surface the plugin uses is identical in 5.0, 5.4, 5.5 … 5.10, so 5.1–5.3 almost certainly match too.
The 5.0 and 5.1 rows list the JGit version used in Archi's build (6.3, 6.6) only for compile checks. The product does not ship it.

`org.eclipse.equinox.security` is a hard dependency of `com.archimatetool.editor` in every 5.x release.
`org.eclipse.jface.notifications` comes with the `org.eclipse.e4.rcp` feature, which every 5.x product includes.
The `NotificationPopup.forDisplay(..).title(.., true).text(..).delay(..)` builder the plugin uses exists unchanged from Eclipse 4.25 to 4.39.
Neither bundle limits the version.

## What limits each version

1. **JGit range `[7.0.0,8.0.0)`**: rules out everything before 5.10. This is the only reason 5.6–5.9 fail.
   The code doesn't need JGit 7. `core` and `plugin` compile against JGit 6.10 with no errors, the same as against 7.0 and 7.7.
2. **Java 21** (`Bundle-RequiredExecutionEnvironment: JavaSE-21`, `options.release = 21`): rules out 5.5 and older.
   Only one Java 21 API is used: `StringBuilder.repeat(CharSequence, int)` in `core/.../xml/Xml.java` (2 call sites).
   Records and `instanceof` patterns are fine on Java 17.
3. **No JGit in the product (Archi 5.0, 5.1)**: these products don't include the `org.eclipse.jgit` feature at all.
   Archi 5.2 was the first to ship JGit (6.6). Also, JGit 6.3 (what Archi 5.0 builds against) has no
   `ProgressMonitor.showDuration(boolean)` (it was added in JGit 6.5), so the `@Override` in `plugin/.../ui/JGitMonitor.java`
   doesn't compile against it.

## Lowering the minimum

This PR doesn't change any plugin code. These are the changes each target would need:

| Target | Changes |
|--------|---------|
| **5.6** (Java 21, JGit 6.10) | Change the manifest to `org.eclipse.jgit;bundle-version="[6.10.0,8.0.0)"`. Build `core` against JGit 6.10 (`core/build.gradle.kts`), or keep 7.7 and add a CI job that compiles and tests against 6.10. Smoke-test sync, clone, branches and history in a real 5.6. |
| **5.2** (Java 17, JGit 6.6) | All of the above with the range `[6.6.0,8.0.0)`. Plus `Bundle-RequiredExecutionEnvironment: JavaSE-17`, `options.release = 17` / toolchain 17, and replace the two `StringBuilder.repeat` calls (e.g. with `"  ".repeat(depth)`). |
| **5.0** (Java 17, no JGit) | The Java 17 changes above. JGit then has to come with the plugin: embed `org.eclipse.jgit` (and `JavaEWAH`, `commons-codec` if needed) in the bundle via `Bundle-ClassPath`, and remove the `org.eclipse.jgit` entry from `Require-Bundle`. That adds roughly 3–4 MB. Embedding also removes all dependence on the JGit version Archi ships. If an embedded JGit older than 6.5 is ever used, also remove the `@Override` on `JGitMonitor.showDuration`. |

Embedding JGit via `Bundle-ClassPath` is also the most robust option for mixed corporate fleets. The plugin then
uses one tested JGit on every Archi version, at the cost of a larger `.archiplugin`.

## How this was determined

This analysis ran in a cloud sandbox whose network policy blocked `www.archimatetool.com`, `download.eclipse.org`,
Maven Central and `services.gradle.org`. So **no Archi binaries were downloaded and the Gradle build could not run**.
GitHub was reachable, so everything was checked against source:

- **Bundled features / JGit version**: `com.archimatetool.editor.product/archi.product` (whether the `org.eclipse.jgit` feature is included)
  and the build repositories in `pom.xml` / `archi.target` (Eclipse simultaneous release and JGit version) at each
  `release_5.x.0` tag of [archimatetool/archi](https://github.com/archimatetool/archi).
- **Java level**: `Bundle-RequiredExecutionEnvironment` of all `com.archimatetool.*` bundles at each tag, plus the commit log.
- **`jface.notifications` / `equinox.security`**: `features/org.eclipse.e4.rcp/feature.xml` and `NotificationPopup.java` in
  [eclipse-platform/eclipse.platform.ui](https://github.com/eclipse-platform/eclipse.platform.ui) at tags R4_25, R4_28, R4_32 and R4_39.
  Also the `Require-Bundle` of `com.archimatetool.editor`.
- **API compatibility (differential compile)**: `javac` (JDK 21) compiled `core` and `plugin` with `-sourcepath` pointing at
  the Archi sources of a given tag and the JGit sources of a given version
  ([eclipse-jgit/jgit](https://github.com/eclipse-jgit/jgit) tags v6.3.0, v6.10.0, v7.0.0, v7.7.0).
  Eclipse platform jars were not available, so every run produces the same set of "missing Eclipse symbol" errors.
  The test is whether the errors in *our* files differ from the 5.10 + JGit 7.7 baseline.
  They are identical for Archi 5.0, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9 and 5.10, and for JGit 6.10 vs 7.7.
  Canary files with calls to nonexistent methods on `IEditorModelManager`, `IArchimateModel`, `IDiagramModel`,
  `EditorManager` and `PersonIdent` were reported as errors. That confirms these types really were resolved against
  the given sources. The Java 17 and JGit 6.3 findings above come from the same method (`--release 17`).

**Not verified:**

- The bundled JRE version, read from `jre/release`. It is inferred from the bundle BREEs.
- OSGi resolution in a running Archi (headless `-console` / `ss`).
- The Gradle test suite against older Archi installs.
- Runtime behaviour on JGit 6.10.

Before announcing support for anything older than 5.10, install the plugin into that Archi:

```
./gradlew :plugin:archiplugin -ParchiHome=<Archi dir>
./gradlew test -ParchiHome=<Archi dir>
```

Then copy the jar into `dropins/`, start Archi with `-consoleLog -console`, and check `ss io.github.p3chys.cooperativearchi`
reports `RESOLVED`/`ACTIVE`.
