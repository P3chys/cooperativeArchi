# cooperativeArchi

Lets several analysts work on the same [Archi](https://www.archimatetool.com) model at the same time, through GitHub,
without merge conflicts in files. A lightweight alternative to coArchi.

- **One button:** *Sync* saves your model, uploads your changes and brings in your colleagues' changes.
- **Merges per element and per property.** If Alice renames an element and Bob moves it in a view, both changes are kept.
  Only when two people change *the same property of the same thing* (e.g. both rename one element) does a dialog ask
  which version to keep.
- **Never breaks the model.** If someone deleted an element that a colleague still uses, it is restored instead of
  leaving a broken reference. Merged models are verified against Archi in the test suite.
- **History** of who changed what, open or restore any older version.
- **Branches** for proposals / what-if work, merged back with the same element-level merge.
- **Optional auto-sync** on every save and/or every N minutes.
- Works offline: Sync saves a local version and uploads it next time.

Requires Archi 5.10 or newer: it uses the JGit 7 bundle that Archi ships from 5.10 on. Archi 5.9 and older can't load it.
See [docs/archi-compatibility.md](docs/archi-compatibility.md) for the per-version analysis and what it would take to
support older versions. Windows now; macOS uses the same plugin, see below.

---

## For analysts

### Install (once)

1. Download `cooperativeArchi-<version>.archiplugin` (from the
   [releases page](https://github.com/P3chys/cooperativeArchi/releases) or from your admin).
2. In Archi: **Help > Manage Plug-ins... > Install New...**, choose the file, restart Archi.
   A **Collaboration** menu and a Sync button appear.

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
| Exchange changes                     | Press **Sync** (toolbar, Collaboration menu or `Ctrl+Alt+Y`) - as often as you like |
| See who changed what                 | **Collaboration > History** (double-click = open that version as a copy) |
| Undo to an older version             | History > right-click > *Restore this version...* |
| Work on a proposal separately        | **Collaboration > Branches... > New branch**; later *Merge into current* |

Shared models are kept in `Documents/Archi/cooperativeArchi/<model>/` (configurable in
*Preferences > Collaboration*). Sync often: small, frequent syncs mean fewer conflicts.

**Conflicts:** the dialog lists only the things both of you changed differently. Click the version to keep in each row
(*Keep all mine / Keep all theirs* for bulk), then *Apply and sync*. *Cancel* changes nothing.

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

Sync = commit the saved file on top of the version you last loaded, then a 3-way merge of *objects* (not text lines)
with the local branch and with GitHub, then upload. The model file is only overwritten when Archi has no unsaved
changes and the file did not change during the sync, so nothing you typed can be lost; anything not loaded yet is
merged on the next Sync.

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
just plugin      # build/dist/cooperativeArchi-<version>.archiplugin
just install     # copy the plugin into your Archi dropins folder (restart Archi)
just cli sync <workspaceDir>   # command line client (also: share, clone, log, branch, switch, merge)
```

The CLI reads the token from `COOPARCHI_TOKEN` (or `GITHUB_TOKEN`) and the author from `COOPARCHI_NAME` /
`COOPARCHI_EMAIL`. It is handy for scripting, CI and support.

### macOS

The plugin is platform independent (Java + SWT, token in the macOS Keychain via Eclipse secure storage). To build on a
Mac point `archiHome` at `/Applications/Archi.app/Contents/Eclipse`; `just install` copies to
`~/Library/Application Support/Archi/dropins`. Analysts install the same `.archiplugin` via *Manage Plug-ins*.

## License

MIT. Icons `sync`, `share`, `clone` are from Eclipse Platform UI (EPL-2.0), see `NOTICE`.
