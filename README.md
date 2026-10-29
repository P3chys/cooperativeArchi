# cooperativeArchi

Lets several analysts work on the same [Archi](https://www.archimatetool.com) model at the same time, through GitHub,
without merge conflicts in files. A lightweight alternative to coArchi.

- **Pull and push, with a review:** *Pull* shows exactly what your colleagues' changes will change in your model
  before applying them; *Push* shows exactly what your changes will change in the model on GitHub before uploading.
  Every added, deleted or changed element, relationship, view, folder and model setting is listed with each changed
  name, documentation, property or reference (old → new). Pure layout (positions, sizes, bend points) is only counted.
- **Merges per element and per property.** If Alice renames an element and Bob moves it in a view, both changes are kept.
  Only when two people change *the same property of the same thing* (e.g. both rename one element) does a dialog ask
  which version to keep.
- **Never breaks the model.** If someone deleted an element that a colleague still uses, it is restored instead of
  leaving a broken reference. Merged models are verified against Archi in the test suite.
- **History** of who changed what, open or restore any older version.
- **Branches** for proposals / what-if work, merged back with the same element-level merge.
- **Optional auto-sync** on every save and/or every N minutes.
- Opening a shared model finds it on `main` or `master`, whichever of the two holds it.
- Works offline: automatic sync saves a local version and uploads it next time.

Requires Archi 5.4+ (Windows and macOS use the same plugin file, see below).

---

## For analysts

### Install (once)

1. Download the file for your Archi version (**Help > About Archi**) from the
   [releases page](https://github.com/P3chys/cooperativeArchi/releases) or from your admin:

   | Archi       | File                                            |
   |-------------|-------------------------------------------------|
   | 5.4 – 5.9   | `cooperativeArchi-<version>-archi5.4.archiplugin`  |
   | 5.10 and up | `cooperativeArchi-<version>-archi5.10.archiplugin` |

   After upgrading Archi across that line, install the matching file again.
2. In Archi: **Help > Manage Plug-ins... > Install New...**, choose the file, restart Archi.
   A **Collaboration** menu and Pull / Push buttons appear.

### Connect to GitHub (once)

**Collaboration > GitHub Access Token...** and follow the steps in the dialog: create a *fine-grained personal access
token* with **Contents: Read and write** on your team's model repositories (plus **Administration: Read and write** if you
will create new shared models), paste it, *Verify and save*. The token is stored encrypted on your computer
(Windows Credential protection / macOS Keychain).

### Daily work

| You want to...                       | Do this |
|--------------------------------------|---------|
| Start sharing a model                | Select it in the Models tree, **Collaboration > Share Model on GitHub...** |
| Open a model a colleague shared      | **Collaboration > Open Shared Model from GitHub...**, paste `owner/repo` |
| Get colleagues' changes              | Press **Pull** (toolbar or Collaboration menu), review, *Apply to my model* |
| Upload your changes                  | Press **Push**, review, *Push to GitHub* (if colleagues pushed first, it offers to Pull before) |
| Both in one go                       | **Collaboration > Sync** (`Ctrl+Alt+Y`): Pull, then Push, each with its review |
| See who changed what                 | **Collaboration > History** (double-click = open that version as a copy) |
| Undo to an older version             | History > right-click > *Restore this version...* |
| Work on a proposal separately        | **Collaboration > Branches... > New branch**; later *Merge into current* |

Shared models are kept in `Documents/Archi/cooperativeArchi/<model>/` (configurable in
*Preferences > Collaboration*). Pull and push often: small, frequent exchanges mean fewer conflicts. In the review
dialog, *Copy as text* copies the whole list of changes.

### Live collaboration

Turn on **Preferences > Collaboration > Live collaboration** to see each other's work within seconds, without pressing
Sync. The plugin then saves a shared model after a short pause in editing (1.5 s by default), uploads the change, and
checks GitHub every few seconds (3 s by default) for colleagues' changes. Incoming changes are applied **into the open
model**: views stay open, your selection stays, and Ctrl+Z still undoes only your own edits. The status bar shows
*Live ● synced 4 s ago*; hover it to see what came in last. If GitHub can't be reached, you get one notice, your work
keeps being saved locally, and it goes up as soon as the connection is back. With everyone in live mode, a change
usually reaches colleagues in 4–9 seconds. No server is needed besides GitHub.

**Conflicts:** the dialog lists only the things both of you changed differently. Click the version to keep in each row
(*Keep all mine / Keep all theirs* for bulk), then *Apply*; the review of the merged result follows. *Cancel* changes
nothing.

---

## How it works

```
Documents/Archi/cooperativeArchi/my-model/
  my-model.archimate      <- the file Archi opens (plain Archi model)
  .coarchi/               <- hidden git repository (history), never edited by hand
```

The repository on GitHub stores the model split into one small XML file per element, relationship and view (folders
as directories) so history and diffs are readable on GitHub, plus a complete `model.archimate` snapshot that anyone can
download and open in Archi without the plugin.

Pull = commit the saved file locally on top of the version you last loaded, then a 3-way merge of *objects* (not text
lines) with GitHub's version; the review compares your model with the merged result. Push = commit the saved file and
upload it, after a review against GitHub's version; it never merges, so it asks you to pull first when GitHub has
changes you don't have. Automatic and live sync do both without review. The model file is only overwritten when Archi has no unsaved
changes and the file did not change during the sync, so nothing you typed can be lost; anything not loaded yet is
merged on the next Pull. The open model is then updated in place: only the objects that changed are touched, matched by
id, outside Archi's undo stack. A check confirms the result equals the merged file; if it doesn't, the model is
closed and reopened instead.

Code layout:

| Path | Content |
|------|---------|
| `core/` | Pure Java engine, no Eclipse: XML model split/assembly (`model`), object-level 3-way merge (`merge`), git sync on JGit (`git`), GitHub API (`github`), CLI (`cli`) |
| `plugin/` | Archi/Eclipse plugin: menus, dialogs, history view, preferences, auto-sync |

## For developers

Needs JDK 21 and a local Archi installation (its jars are the compile target; default `E:/Archi`, override with
`-ParchiHome=...` or in `gradle.properties`).

```bash
just test        # core unit + two-analyst sync tests; also opens merged models in real Archi if found
./gradlew :plugin:test   # in-place model update (headless, on the Archi model jars from archiHome)
just plugin      # build/dist/cooperativeArchi-<version>.archiplugin
just install     # copy the plugin into your Archi dropins folder (close Archi first, then start it again)
just second-archi [name]       # Windows: a second Archi as another analyst, to try live collaboration on one machine
just cli pull <workspaceDir>   # command line client; pull/push print the change list (also: sync, share, clone, log, branch, switch, merge)
```

**Windows: don't use PowerShell 7 from the Microsoft Store for `just install` / `just second-archi`, or to start Archi.**
Store apps run in a sandbox that redirects their writes to `%APPDATA%` (where Archi keeps dropins and settings) into a
private copy, and every program they start inherits it. A plug-in installed from there is invisible to Archi started
from the Start menu, and an Archi started from there doesn't see the real plug-ins. Use Git Bash, cmd, Windows PowerShell,
or PowerShell 7 installed with winget/MSI. `just install` refuses to run in that sandbox.

The CLI reads the token from `COOPARCHI_TOKEN` (or `GITHUB_TOKEN`) and the author from `COOPARCHI_NAME` /
`COOPARCHI_EMAIL`. It is handy for scripting, CI and support.

### macOS

The plugin is platform independent (Java + SWT, token in the macOS Keychain via Eclipse secure storage). To build on a
Mac point `archiHome` at `/Applications/Archi.app/Contents/Eclipse`; `just install` copies to
`~/Library/Application Support/Archi/dropins`. Analysts install the same `.archiplugin` via *Manage Plug-ins*.

## License

MIT. Icons `sync`, `share`, `clone` are from Eclipse Platform UI (EPL-2.0), see `NOTICE`.
