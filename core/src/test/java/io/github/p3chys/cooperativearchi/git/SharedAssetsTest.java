package io.github.p3chys.cooperativearchi.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.p3chys.cooperativearchi.TestModels;

/** The Assets folder next to the model travels with it: Alice and Bob through a shared (local bare) repository. */
class SharedAssetsTest {

    @TempDir
    Path tmp;
    String remote;
    SyncOptions alice;
    SyncOptions bob;

    @BeforeEach
    void setUp() throws Exception {
        Path remoteDir = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remoteDir.toFile()).setInitialBranch("main").call().close();
        remote = remoteDir.toUri().toString();
        alice = SyncOptions.of(null, new PersonIdent("Alice", "alice@example.com"), ConflictResolver.FAIL);
        bob = SyncOptions.of(null, new PersonIdent("Bob", "bob@example.com"), ConflictResolver.FAIL);
    }

    @Test
    void addedChangedAndDeletedAssetsReachTheColleague() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "process/flow.png", "v1");
            write(a, "tariff.csv", "a;b");
            assertThat(a.hasLocalChanges()).isTrue();
            SyncResult ra = a.sync(alice);
            assertThat(ra.committed()).isEqualTo("Added 2 assets");
            assertThat(ra.needsReload()).isFalse();
            assertThat(a.hasLocalChanges()).isFalse();

            SyncResult rb = b.sync(bob);
            assertThat(rb.received()).isEqualTo("Added 2 assets");
            assertThat(asset(b, "process/flow.png")).doesNotExist(); // like the model file: only when applied
            assertThat(b.applySync(rb)).isTrue();
            assertThat(asset(b, "process/flow.png")).hasContent("v1");
            assertThat(asset(b, "tariff.csv")).hasContent("a;b");
            assertThat(b.hasLocalChanges()).isFalse();
            assertThat(b.sync(bob).nothingHappened()).isTrue();

            write(b, "process/flow.png", "v2");
            Files.delete(asset(b, "tariff.csv"));
            assertThat(b.sync(bob).committed()).isEqualTo("Changed asset 'process/flow.png', deleted asset 'tariff.csv'");

            SyncResult ra2 = a.sync(alice);
            assertThat(a.applySync(ra2)).isTrue();
            assertThat(asset(a, "process/flow.png")).hasContent("v2");
            assertThat(asset(a, "tariff.csv")).doesNotExist();
        }
    }

    @Test
    void assetsAndModelChangesMergeTogether() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "a.txt", "from Alice");
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            write(b, "b.txt", "from Bob");
            assertThat(a.sync(alice).committed()).isEqualTo("Changed Business Actor 'Client', added asset 'a.txt'");

            SyncResult rb = b.sync(bob);
            assertThat(rb.received()).contains("Client", "added asset 'a.txt'");
            assertThat(b.applySync(rb)).isTrue();
            SyncResult ra = a.sync(alice);
            assertThat(a.applySync(ra)).isTrue();

            for (SharedModel m : List.of(a, b)) {
                assertThat(asset(m, "a.txt")).hasContent("from Alice");
                assertThat(asset(m, "b.txt")).hasContent("from Bob");
                assertThat(Files.readString(m.modelFile())).contains("name=\"Client\"");
            }
        }
    }

    @Test
    void bothVersionsOfAnAssetChangedByBothAreKept() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "flow.png", "v1");
            a.sync(alice);
            b.applySync(b.sync(bob));

            write(a, "flow.png", "Alice's");
            write(b, "flow.png", "Bob's");
            a.sync(alice);
            SyncResult rb = b.sync(bob);
            assertThat(rb.notes()).anySatisfy(n -> assertThat(n).contains("flow.png", "yours is saved as 'flow (conflict "));
            assertThat(b.applySync(rb)).isTrue();
            a.applySync(a.sync(alice));

            for (SharedModel m : List.of(a, b)) {
                assertThat(asset(m, "flow.png")).hasContent("Alice's");
                assertThat(names(m)).hasSize(2).anySatisfy(n -> assertThat(n).startsWith("flow (conflict ").endsWith(").png"));
                assertThat(names(m).stream().filter(n -> n.startsWith("flow (")).map(n -> read(asset(m, n))))
                        .containsExactly("Bob's");
            }
        }
    }

    @Test
    void assetDeletedByOneAndChangedByTheOtherStays() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "sla.pdf", "v1");
            a.sync(alice);
            b.applySync(b.sync(bob));

            Files.delete(asset(a, "sla.pdf"));
            write(b, "sla.pdf", "v2");
            a.sync(alice);
            SyncResult rb = b.sync(bob);
            assertThat(rb.notes()).anySatisfy(n -> assertThat(n).contains("sla.pdf", "it is kept"));
            b.applySync(rb);
            a.applySync(a.sync(alice));
            assertThat(asset(a, "sla.pdf")).hasContent("v2");
            assertThat(asset(b, "sla.pdf")).hasContent("v2");
        }
    }

    @Test
    void assetEditedWhileTheSyncRanIsNotOverwritten() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "flow.png", "v1");
            a.sync(alice);
            b.applySync(b.sync(bob));

            write(a, "flow.png", "v2");
            a.sync(alice);
            SyncResult rb = b.sync(bob);
            write(b, "flow.png", "Bob, during the sync");
            assertThat(b.applySync(rb)).isTrue();
            assertThat(asset(b, "flow.png")).hasContent("v2");
            assertThat(rb.notes()).anySatisfy(n -> assertThat(n).contains("changed on this computer", "flow (conflict "));
            assertThat(names(b).stream().filter(n -> n.startsWith("flow (")).map(n -> read(asset(b, n))))
                    .containsExactly("Bob, during the sync");

            // and an asset deleted for everyone stays when it was edited meanwhile
            Files.delete(asset(a, "flow.png"));
            a.sync(alice);
            SyncResult rb2 = b.sync(bob);
            write(b, "flow.png", "still needed");
            assertThat(b.applySync(rb2)).isTrue();
            assertThat(asset(b, "flow.png")).hasContent("still needed");
        }
    }

    @Test
    void pullAndPushShowTheAssetsInTheirReview() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            List<String> reviews = new ArrayList<>();
            ChangeReviewer recording = (diff, direction) -> reviews.add(direction + "\n" + diff.toText());
            write(a, "flow.png", "v1");
            SyncResult pushed = a.push(alice.withReviewer(recording));
            assertThat(pushed.pushed()).isTrue();
            assertThat(pushed.committed()).isEqualTo("Added asset 'flow.png'");

            SyncResult pulled = b.pull(bob.withReviewer(recording));
            assertThat(pulled.received()).isEqualTo("Added asset 'flow.png'");
            assertThat(b.applySync(pulled)).isTrue();
            assertThat(asset(b, "flow.png")).hasContent("v1");
            assertThat(reviews).hasSize(2).allSatisfy(r -> assertThat(r).contains("Assets\n  + flow.png\n      File: 2 B"));

            // cancelling the review of a pull leaves the folder alone
            write(a, "flow.png", "v2");
            a.push(alice);
            try {
                b.pull(bob.withReviewer((diff, direction) -> false));
            } catch (SyncCancelledException expected) {
                // nothing applied
            }
            assertThat(asset(b, "flow.png")).hasContent("v1");
        }
    }

    @Test
    void sharingAModelTakesItsAssetsFolderAlongAndACloneGetsIt() throws Exception {
        Path source = tmp.resolve("work/source.archimate");
        Files.createDirectories(source.getParent().resolve("Assets/docs"));
        Files.writeString(source, TestModels.text());
        Files.writeString(source.getParent().resolve("Assets/docs/SLA 2026.pdf"), "pdf");
        try (SharedModel a = SharedModel.share(source, tmp.resolve("alice"), remote)) {
            assertThat(a.sync(alice).pushed()).isTrue();
            try (SharedModel b = cloneAsBob()) {
                assertThat(asset(b, "docs/SLA 2026.pdf")).hasContent("pdf");
                assertThat(b.hasLocalChanges()).isFalse();
            }
        }
    }

    @Test
    void colleagueOnAnOlderPluginDoesNotDeleteTheAssets() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "flow.png", "v1");
            a.sync(alice);
            b.applySync(b.sync(bob));

            // an older plugin commits the model only: its version has no Assets and no marker
            try (Git remoteRepo = Git.open(tmp.resolve("remote.git").toFile())) {
                commitWithoutAssets(remoteRepo.getRepository(), "main");
            }
            SyncResult ra = a.sync(alice);
            assertThat(a.applySync(ra)).isTrue();
            assertThat(asset(a, "flow.png")).hasContent("v1");

            // the next upload carries them again, and a fresh clone gets them
            write(a, "more.txt", "x");
            assertThat(a.sync(alice).committed()).isEqualTo("Added asset 'more.txt'");
            b.applySync(b.sync(bob));
            assertThat(asset(b, "flow.png")).hasContent("v1");
            assertThat(asset(b, "more.txt")).hasContent("x");
            try (SharedModel c = SharedModel.cloneFrom(remote, tmp.resolve("carol"), bob)) {
                assertThat(asset(c, "flow.png")).hasContent("v1");
            }
        }
    }

    @Test
    void workspaceOfAnOlderPluginGetsTheAssetsAfterTheUpgradeAndDeletesNone() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            write(a, "flow.png", "v1");
            a.sync(alice);
            SyncResult rb = b.sync(bob);
            b.applySync(rb);
            // as the older plugin left it: the version is loaded, but the folder was never written
            Files.delete(asset(b, "flow.png"));
            Files.delete(asset(b, "flow.png").getParent());
            forgetAssets(b);
            write(b, "local.txt", "Bob had this");

            SyncResult after = b.sync(bob);
            assertThat(after.committed()).isEqualTo("Added asset 'local.txt'");
            assertThat(asset(b, "flow.png")).hasContent("v1");
            a.applySync(a.sync(alice));
            assertThat(asset(a, "flow.png")).hasContent("v1");
            assertThat(asset(a, "local.txt")).hasContent("Bob had this");
        }
    }

    @Test
    void branchesKeepTheirOwnAssets() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            write(a, "main.txt", "m");
            a.sync(alice);
            a.createBranch("proposal");
            write(a, "proposal.txt", "p");
            a.sync(alice);

            a.loadBranchContent(a.switchBranch("main"));
            assertThat(names(a)).containsExactly("main.txt");
            a.loadBranchContent(a.switchBranch("proposal"));
            assertThat(names(a)).containsExactly("main.txt", "proposal.txt");

            a.loadBranchContent(a.switchBranch("main"));
            SyncResult merged = a.sync(alice.withMergeFrom("proposal"));
            a.applySync(merged);
            assertThat(names(a)).containsExactly("main.txt", "proposal.txt");
        }
    }

    @Test
    void restoringAVersionPutsItsMissingAssetsBack() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            write(a, "old.txt", "o");
            a.sync(alice);
            HistoryEntry withAsset = a.history(1).get(0);
            Files.delete(asset(a, "old.txt"));
            write(a, "new.txt", "n");
            a.sync(alice);

            a.restoreVersion(withAsset.id(), withAsset.time());
            assertThat(asset(a, "old.txt")).hasContent("o");
            assertThat(asset(a, "new.txt")).hasContent("n");
        }
    }

    @Test
    void changeOfTheSameSizeAtTheSameFileTimeIsSeen() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            write(a, "flow.png", "v1");
            a.sync(alice);
            var time = Files.getLastModifiedTime(asset(a, "flow.png"));
            write(a, "flow.png", "v2");
            Files.setLastModifiedTime(asset(a, "flow.png"), time); // as a coarse file clock would leave it
            assertThat(a.hasLocalChanges()).isTrue();
            assertThat(a.sync(alice).committed()).isEqualTo("Changed asset 'flow.png'");
        }
    }

    @Test
    void liveCheckSeesAFileDroppedIntoTheFolderOnceItHasSettled() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            assertThat(a.sharedFilesChanged()).isFalse();
            write(a, "flow.png", "v1");
            assertThat(a.sharedFilesChanged()).isFalse(); // written this moment: a copy may still be running
            java.nio.file.attribute.FileTime earlier = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10_000);
            Files.setLastModifiedTime(asset(a, "flow.png"), earlier);
            assertThat(a.sharedFilesChanged()).isTrue();
            assertThat(a.sync(alice).committed()).isEqualTo("Added asset 'flow.png'");
            assertThat(a.sharedFilesChanged()).isFalse();

            Files.writeString(a.modelFile().getParent().resolve("documentation.rules"), "tag red risk");
            Files.setLastModifiedTime(a.modelFile().getParent().resolve("documentation.rules"), earlier);
            assertThat(a.sharedFilesChanged()).isTrue();
            Files.delete(asset(a, "flow.png"));
            a.sync(alice);
            assertThat(a.sharedFilesChanged()).isFalse();
        }
    }

    @Test
    void rulesFileNextToTheModelIsSharedLikeAnAsset() throws Exception {
        Path source = tmp.resolve("work/source.archimate");
        Files.createDirectories(source.getParent());
        Files.writeString(source, TestModels.text());
        Files.writeString(source.getParent().resolve("documentation.rules"), "tag red risk");
        Files.writeString(source.getParent().resolve("notes.txt"), "private");
        try (SharedModel a = SharedModel.share(source, tmp.resolve("alice"), remote)) {
            assertThat(a.sync(alice).pushed()).isTrue();
            try (SharedModel b = cloneAsBob()) {
                assertThat(beside(b, "documentation.rules")).hasContent("tag red risk");
                assertThat(beside(b, "notes.txt")).doesNotExist(); // only the named files, never the whole folder
                assertThat(b.hasLocalChanges()).isFalse();

                List<String> reviews = new ArrayList<>();
                Files.writeString(beside(b, "documentation.rules"), "tag red risk\ntag yellow todo");
                Files.writeString(beside(b, "other.rules"), "not shared");
                SyncResult pushed = b.push(bob.withReviewer((diff, direction) -> reviews.add(diff.toText())));
                assertThat(pushed.committed()).isEqualTo("Changed file 'documentation.rules'");
                assertThat(reviews).singleElement().asString().contains("Shared files\n  ~ documentation.rules");

                // both change it: the colleague's version keeps the name, the other stays next to it
                Files.writeString(beside(a, "documentation.rules"), "tag blue idea");
                SyncResult ra = a.sync(alice);
                assertThat(ra.notes()).anySatisfy(n -> assertThat(n).contains("File 'documentation.rules'", "(conflict "));
                assertThat(a.applySync(ra)).isTrue();
                assertThat(beside(a, "documentation.rules")).hasContent("tag red risk\ntag yellow todo");
                try (Stream<Path> files = Files.list(a.modelFile().getParent())) {
                    assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("documentation (conflict "))
                            .map(n -> read(beside(a, n)))).containsExactly("tag blue idea");
                }

                Files.delete(beside(a, "documentation.rules"));
                assertThat(a.sync(alice).committed()).isEqualTo("Deleted file 'documentation.rules'");
                b.applySync(b.sync(bob));
                assertThat(beside(b, "documentation.rules")).doesNotExist();
            }
        }
    }

    @Test
    void manyMergesByAnOlderPluginAreReadWithoutRunningAway() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            write(a, "flow.png", "v1");
            a.sync(alice);
            try (Git remoteRepo = Git.open(tmp.resolve("remote.git").toFile())) {
                for (int i = 0; i < 300; i++) {
                    mergeWithoutAssets(remoteRepo.getRepository(), "main");
                }
            }
            SyncResult r = a.sync(alice);
            assertThat(a.applySync(r)).isTrue();
            assertThat(asset(a, "flow.png")).hasContent("v1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "documentation.rules", "documentation (conflict 1a2b3c4).rules", "documentation.published",
            "documentation (conflict 1a2b3c4).published" })
    void namedFilesNextToTheModelAreShared(String path) {
        assertThat(SharedFiles.valid(path)).isTrue();
        assertThat(SharedFiles.owns(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "other.rules", "model.archimate", "documentation.rules.bak", "sub/documentation.rules",
            "documentation (conflict xyz).rules", ".coarchi/config", "source.archimate" })
    void otherFilesNextToTheModelAreNot(String path) {
        assertThat(SharedFiles.valid(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "Assets/a.png", "Assets/docs/SLA 2026.pdf", "Assets/Průběh sjednání.png", "Assets/.hidden" })
    void namesEveryComputerAcceptsAreShared(String path) {
        assertThat(SharedFiles.valid(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "Assets/../evil.txt", "Assets/a/../../evil.txt", "Assets//a.png", "Assets/", "Assets",
            "model/model.xml", "Assets/a:b.png", "Assets/a\\b.png", "Assets/con.txt", "Assets/NUL", "Assets/a.", "Assets/a ",
            "Assets/.git/config", "Assets/a\tb", "Assets/what?.png", "/etc/passwd", "Assets/./a.png" })
    void namesThatCouldEscapeOrBreakAComputerAreNot(String path) {
        assertThat(SharedFiles.valid(path)).isFalse();
    }

    // ------------------------------------------------------------ helpers

    /** A commit on top of {@code branch} with the same model and nothing of the assets, as an older plugin writes. */
    private static void commitWithoutAssets(Repository r, String branch) throws Exception {
        try (ObjectInserter ins = r.newObjectInserter(); RevWalk w = new RevWalk(r); TreeWalk tw = new TreeWalk(r)) {
            RevCommit tip = w.parseCommit(r.resolve(Constants.R_HEADS + branch));
            TreeFormatter tree = new TreeFormatter();
            tw.addTree(tip.getTree());
            while (tw.next()) {
                String name = tw.getNameString();
                if (!name.equals(SharedFiles.DIR) && !name.equals(SharedFiles.MARKER)) {
                    tree.append(name, tw.getFileMode(0), tw.getObjectId(0));
                }
            }
            tree.append("zz-touched-by-old-plugin.txt", FileMode.REGULAR_FILE, ins.insert(Constants.OBJ_BLOB, new byte[] { 1 }));
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(ins.insert(tree));
            cb.setParentId(tip);
            PersonIdent who = new PersonIdent("Carol", "carol@example.com");
            cb.setAuthor(who);
            cb.setCommitter(who);
            cb.setMessage("Changed something with an older plugin");
            ObjectId commit = ins.insert(cb);
            ins.flush();
            RefUpdate u = r.updateRef(Constants.R_HEADS + branch);
            u.setNewObjectId(commit);
            assertThat(u.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
        }
    }

    /**
     * A merge commit on top of {@code branch}, of its tip and a side commit, neither carrying assets: what an older
     * plugin leaves each time it syncs while colleagues uploaded.
     */
    private static void mergeWithoutAssets(Repository r, String branch) throws Exception {
        try (ObjectInserter ins = r.newObjectInserter(); RevWalk w = new RevWalk(r); TreeWalk tw = new TreeWalk(r)) {
            RevCommit tip = w.parseCommit(r.resolve(Constants.R_HEADS + branch));
            TreeFormatter tree = new TreeFormatter();
            tw.addTree(tip.getTree());
            while (tw.next()) {
                String name = tw.getNameString();
                if (!name.equals(SharedFiles.DIR) && !name.equals(SharedFiles.MARKER)) {
                    tree.append(name, tw.getFileMode(0), tw.getObjectId(0));
                }
            }
            ObjectId treeId = ins.insert(tree);
            PersonIdent who = new PersonIdent("Carol", "carol@example.com");
            ObjectId[] made = new ObjectId[2];
            for (int i = 0; i < 2; i++) {
                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(treeId);
                if (i == 0) {
                    cb.setParentId(tip);
                } else {
                    cb.setParentIds(tip, made[0]);
                }
                cb.setAuthor(who);
                cb.setCommitter(who);
                cb.setMessage(i == 0 ? "Side" : "Merge by an older plugin");
                made[i] = ins.insert(cb);
            }
            ins.flush();
            RefUpdate u = r.updateRef(Constants.R_HEADS + branch);
            u.setNewObjectId(made[1]);
            assertThat(u.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
        }
    }

    private static Path beside(SharedModel m, String name) {
        return m.modelFile().getParent().resolve(name);
    }

    /** Removes what only a plugin that knows about assets records in the workspace. */
    private static void forgetAssets(SharedModel m) throws Exception {
        Path gitDir = m.modelFile().getParent().resolve(SharedModel.GIT_DIR);
        try (Repository r = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setBare().build()) {
            StoredConfig cfg = r.getConfig();
            cfg.unset("cooperativearchi", null, "assets");
            cfg.save();
        }
    }

    private SharedModel shareAsAlice() throws Exception {
        Path source = tmp.resolve("source.archimate");
        Files.writeString(source, TestModels.text());
        SharedModel a = SharedModel.share(source, tmp.resolve("alice"), remote);
        assertThat(a.sync(alice).pushed()).isTrue();
        return a;
    }

    private SharedModel cloneAsBob() throws Exception {
        return SharedModel.cloneFrom(remote, tmp.resolve("bob"), bob);
    }

    private static Path asset(SharedModel m, String name) {
        return m.modelFile().getParent().resolve("Assets").resolve(name);
    }

    private static void write(SharedModel m, String name, String content) throws Exception {
        Files.createDirectories(asset(m, name).getParent());
        Files.writeString(asset(m, name), content);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The files of the Assets folder, by path inside it, sorted. */
    private static List<String> names(SharedModel m) throws Exception {
        Path dir = m.modelFile().getParent().resolve("Assets");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).map(f -> dir.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private static void edit(SharedModel m, String from, String to) throws Exception {
        String text = Files.readString(m.modelFile());
        assertThat(text).contains(from);
        Files.writeString(m.modelFile(), text.replace(from, to));
    }
}
