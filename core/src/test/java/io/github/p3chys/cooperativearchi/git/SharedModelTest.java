package io.github.p3chys.cooperativearchi.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.p3chys.cooperativearchi.TestModels;
import io.github.p3chys.cooperativearchi.merge.Conflict.Side;
import io.github.p3chys.cooperativearchi.model.ModelGraph;
import io.github.p3chys.cooperativearchi.xml.Xml;

/** Two analysts, Alice and Bob, working on one model through a shared (local bare) repository. */
class SharedModelTest {

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
    void twoAnalystsEditDifferentThingsAndConverge() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");

            SyncResult rb = b.sync(bob);
            assertThat(rb.pushed()).isTrue();
            assertThat(rb.needsReload()).isFalse();

            SyncResult ra = a.sync(alice);
            assertThat(ra.committed()).isEqualTo("Changed Business Actor 'Client'");
            assertThat(ra.received()).contains("Salesforce");
            assertThat(ra.pushed()).isTrue();
            assertThat(a.applySync(ra)).isTrue();

            SyncResult rb2 = b.sync(bob);
            assertThat(rb2.received()).contains("Client");
            assertThat(b.applySync(rb2)).isTrue();

            assertThat(semantic(a)).isEqualTo(semantic(b));
            assertThat(semantic(a)).contains("name=\"Client\"", "name=\"Salesforce\"");
            assertThat(a.history(10)).extracting(HistoryEntry::author).contains("Alice", "Bob");
        }
    }

    @Test
    void syncWithoutChangesDoesNothing() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            SyncResult r = a.sync(alice);
            assertThat(r.nothingHappened()).isTrue();
            assertThat(r.describe()).isEqualTo("Already up to date");
            assertThat(a.hasLocalChanges()).isFalse();
        }
    }

    @Test
    void reformattedFileByArchiIsNotAChange() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            // Bob's file is the generated snapshot; Archi re-saves it in its own formatting
            byte[] archiStyle = TestModels.text().getBytes(StandardCharsets.UTF_8);
            Files.write(b.modelFile(), archiStyle);
            assertThat(b.hasLocalChanges()).isFalse();
            assertThat(b.sync(bob).nothingHappened()).isTrue();
        }
    }

    @Test
    void conflictIsResolvedByChoice() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            edit(b, "name=\"Customer\"", "name=\"Buyer\"");
            b.sync(bob);

            SyncOptions takeTheirs = SyncOptions.of(null, alice.author(), (session, label) -> {
                session.conflicts().forEach(c -> c.choose(Side.THEIRS));
                return true;
            });
            SyncResult r = a.sync(takeTheirs);
            assertThat(r.conflictsResolved()).isEqualTo(1);
            a.applySync(r);
            assertThat(semantic(a)).contains("name=\"Buyer\"");
        }
    }

    @Test
    void cancelledConflictChangesNothing() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            edit(b, "name=\"Customer\"", "name=\"Buyer\"");
            b.sync(bob);
            byte[] before = Files.readAllBytes(a.modelFile());

            SyncOptions cancel = SyncOptions.of(null, alice.author(), (s, label) -> false);
            assertThatThrownBy(() -> a.sync(cancel)).isInstanceOf(SyncCancelledException.class);

            assertThat(Files.readAllBytes(a.modelFile())).isEqualTo(before);
            SyncResult again = b.sync(bob);
            assertThat(again.received()).isNull();
        }
    }

    @Test
    void saveDuringSyncIsNeverOverwrittenAndMergedNextTime() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            b.sync(bob);

            SyncResult r = a.sync(alice);
            assertThat(r.needsReload()).isTrue();
            // Alice saves while the sync was running
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            assertThat(a.applySync(r)).isFalse();
            assertThat(semantic(a)).contains("Client").doesNotContain("Salesforce");

            SyncResult r2 = a.sync(alice);
            assertThat(a.applySync(r2)).isTrue();
            assertThat(semantic(a)).contains("Client", "Salesforce");
        }
    }

    @Test
    void deferredReloadKeepsAllWork() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            b.sync(bob);
            a.sync(alice); // Archi had unsaved changes: Alice does not reload
            edit(a, "name=\"Customer\"", "name=\"Client\"");

            SyncResult r = a.sync(alice);
            assertThat(a.applySync(r)).isTrue();
            assertThat(semantic(a)).contains("Client", "Salesforce");
            b.applySync(b.sync(bob));
            assertThat(semantic(b)).isEqualTo(semantic(a));
        }
    }

    @Test
    void branchesCanBeCreatedSwitchedAndMerged() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            a.createBranch("draft");
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            assertThat(a.sync(alice).pushed()).isTrue();

            b.sync(bob);
            assertThat(b.branches()).containsExactly("draft", "main");
            assertThat(semantic(b)).doesNotContain("Client");

            a.loadBranchContent(a.switchBranch("main"));
            assertThat(semantic(a)).doesNotContain("Client");
            SyncResult merged = a.sync(alice.withMergeFrom("draft"));
            assertThat(a.applySync(merged)).isTrue();
            assertThat(semantic(a)).contains("Client");

            b.applySync(b.sync(bob));
            assertThat(semantic(b)).contains("Client");
        }
    }

    @Test
    void deletionOnBranchIsMergedBack() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            a.createBranch("draft");
            a.sync(alice);
            a.loadBranchContent(a.switchBranch("main"));
            edit(a, "id=\"id-proc\"/>", "id=\"id-proc\"/><element xsi:type=\"archimate:BusinessActor\" name=\"Temp\" id=\"id-temp\"/>");
            a.sync(alice);

            a.loadBranchContent(a.switchBranch("draft"));
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            a.sync(alice);
            a.applySync(a.sync(alice.withMergeFrom("main")));
            assertThat(semantic(a)).contains("id-temp");
            edit(a, "<element xsi:type=\"archimate:BusinessActor\" name=\"Temp\" id=\"id-temp\"/>", "");
            a.sync(alice);

            a.loadBranchContent(a.switchBranch("main"));
            edit(a, "name=\"CRM\"", "name=\"Salesforce\"");
            a.sync(alice);
            assertThat(semantic(a)).contains("id-temp");
            a.applySync(a.sync(alice.withMergeFrom("draft")));
            assertThat(semantic(a)).doesNotContain("id-temp");
        }
    }

    @Test
    void offlineSyncCommitsLocallyAndUploadsLater() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            String realRemote = a.remoteUrl();
            a.setRemoteUrl(tmp.resolve("missing.git").toUri().toString());
            edit(a, "name=\"Customer\"", "name=\"Client\"");

            SyncResult r = a.sync(alice);
            assertThat(r.offlineReason()).isNotNull();
            assertThat(r.committed()).isNotNull();
            assertThat(a.history(5).get(0).uploaded()).isFalse();

            a.setRemoteUrl(realRemote);
            assertThat(a.sync(alice).pushed()).isTrue();
        }
    }

    @Test
    void cloneOfEmptyRepositoryFailsClearly() {
        assertThatThrownBy(() -> SharedModel.cloneFrom(remote, tmp.resolve("bob"), bob))
                .hasMessageContaining("does not contain a model");
        assertThat(tmp.resolve("bob")).doesNotExist();
    }

    @Test
    void pushShowsChangesAgainstGitHubAndPullAgainstTheLocalModel() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            List<String> reviewed = new ArrayList<>();
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            SyncResult pushed = b.push(bob.withReviewer(recording(reviewed)));
            assertThat(pushed.pushed()).isTrue();
            assertThat(pushed.committed()).isEqualTo("Changed Application Component 'Salesforce'");
            assertThat(reviewed).containsExactly("PUSH\nElements\n  ~ Application Component 'Salesforce'\n      Name: CRM → Salesforce");

            // Alice's own unsent edit is not part of what her pull changes
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            reviewed.clear();
            SyncResult pulled = a.pull(alice.withReviewer(recording(reviewed)));
            assertThat(reviewed).containsExactly("PULL\nElements\n  ~ Application Component 'Salesforce'\n      Name: CRM → Salesforce");
            assertThat(pulled.pushed()).isFalse();
            assertThat(pulled.committed()).isNull();
            assertThat(pulled.diff().entries()).hasSize(1);
            assertThat(a.applySync(pulled)).isTrue();
            assertThat(semantic(a)).contains("Client", "Salesforce");

            reviewed.clear();
            assertThat(a.push(alice.withReviewer(recording(reviewed))).pushed()).isTrue();
            assertThat(reviewed).containsExactly("PUSH\nElements\n  ~ Business Actor 'Client'\n      Name: Customer → Client");
            assertThat(b.applySync(b.pull(bob))).isTrue();
            assertThat(semantic(b)).isEqualTo(semantic(a));
        }
    }

    @Test
    void pushIsRefusedUntilColleaguesChangesArePulled() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            b.push(bob);
            edit(a, "name=\"Customer\"", "name=\"Client\"");

            assertThatThrownBy(() -> a.push(alice)).isInstanceOf(PullFirstException.class);
            assertThat(b.pull(bob).received()).isNull();

            assertThat(a.applySync(a.pull(alice))).isTrue();
            assertThat(a.push(alice).pushed()).isTrue();
            assertThat(b.pull(bob).received()).contains("Client");
        }
    }

    @Test
    void cancelledReviewChangesNothing() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            b.push(bob);
            byte[] before = Files.readAllBytes(a.modelFile());

            assertThatThrownBy(() -> a.pull(alice.withReviewer((d, x) -> false))).isInstanceOf(SyncCancelledException.class);
            assertThat(Files.readAllBytes(a.modelFile())).isEqualTo(before);
            SyncResult again = a.pull(alice);
            assertThat(again.received()).contains("Salesforce");
            assertThat(a.applySync(again)).isTrue();

            edit(a, "name=\"Customer\"", "name=\"Client\"");
            assertThatThrownBy(() -> a.push(alice.withReviewer((d, x) -> false))).isInstanceOf(SyncCancelledException.class);
            assertThat(b.pull(bob).received()).isNull();
            assertThat(a.push(alice).pushed()).isTrue();
        }
    }

    @Test
    void pullAndPushWithoutChangesAreUpToDate() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            List<String> reviewed = new ArrayList<>();
            assertThat(a.pull(alice.withReviewer(recording(reviewed))).describe()).isEqualTo("Already up to date");
            assertThat(a.push(alice.withReviewer(recording(reviewed))).describe()).isEqualTo("Already up to date");
            assertThat(reviewed).isEmpty();
        }
    }

    @Test
    void firstPushUploadsTheWholeModel() throws Exception {
        Path source = tmp.resolve("source.archimate");
        Files.writeString(source, TestModels.text());
        try (SharedModel a = SharedModel.share(source, tmp.resolve("alice"), remote)) {
            List<String> reviewed = new ArrayList<>();
            SyncResult r = a.push(alice.withReviewer(recording(reviewed)));
            assertThat(r.committed()).isEqualTo("First version of 'Test Model'");
            assertThat(reviewed).singleElement().asString().contains("+ Business Actor 'Customer'", "+ View 'Main View'");
        }
    }

    @Test
    void newBranchIsComparedWithMainOnGitHub() throws Exception {
        try (SharedModel a = shareAsAlice()) {
            a.createBranch("draft");
            edit(a, "name=\"Customer\"", "name=\"Client\"");
            List<String> reviewed = new ArrayList<>();
            assertThat(a.push(alice.withReviewer(recording(reviewed))).pushed()).isTrue();
            assertThat(reviewed).containsExactly("PUSH\nElements\n  ~ Business Actor 'Client'\n      Name: Customer → Client");
        }
    }

    @Test
    void cloneFindsTheModelOnMaster() throws Exception {
        shareAsAlice().close();
        try (Git remoteRepo = Git.open(tmp.resolve("remote.git").toFile())) {
            moveBranch(remoteRepo.getRepository(), "main", "master");
        }
        try (SharedModel b = cloneAsBob()) {
            assertThat(b.branch()).isEqualTo("master");
            assertThat(semantic(b)).contains("Customer");
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            assertThat(b.push(bob).pushed()).isTrue();
        }
    }

    @Test
    void cloneSkipsADefaultBranchWithoutModel() throws Exception {
        shareAsAlice().close();
        try (Git remoteRepo = Git.open(tmp.resolve("remote.git").toFile())) {
            moveBranch(remoteRepo.getRepository(), "main", "master");
            // e.g. created on GitHub with a README: main is the default branch but has no model
            commitReadme(remoteRepo.getRepository(), "main");
        }
        try (SharedModel b = cloneAsBob()) {
            assertThat(b.branch()).isEqualTo("master");
            assertThat(semantic(b)).contains("Customer");
        }
    }

    @Test
    void workspaceFollowsTheModelFromMainToMaster() throws Exception {
        try (SharedModel a = shareAsAlice(); SharedModel b = cloneAsBob()) {
            try (Git remoteRepo = Git.open(tmp.resolve("remote.git").toFile())) {
                moveBranch(remoteRepo.getRepository(), "main", "master");
            }
            edit(b, "name=\"CRM\"", "name=\"Salesforce\"");
            assertThat(b.push(bob).pushed()).isTrue();
            assertThat(b.branch()).isEqualTo("master");

            SyncResult r = a.pull(alice);
            assertThat(a.branch()).isEqualTo("master");
            assertThat(r.received()).contains("Salesforce");
            assertThat(a.branches()).containsExactly("master");
        }
    }

    @Test
    void cloneOfRepositoryWithoutModelFailsClearly() throws Exception {
        try (Git remoteRepo = Git.open(tmp.resolve("remote.git").toFile())) {
            commitReadme(remoteRepo.getRepository(), "master");
        }
        assertThatThrownBy(() -> cloneAsBob()).hasMessageContaining("does not contain a model yet (looked in main and master)");
    }

    // ------------------------------------------------------------ helpers

    private static ChangeReviewer recording(List<String> into) {
        return (diff, direction) -> into.add(direction + "\n" + diff.toText());
    }

    private static void moveBranch(Repository r, String from, String to) throws Exception {
        RefUpdate create = r.updateRef(Constants.R_HEADS + to);
        create.setNewObjectId(r.resolve(Constants.R_HEADS + from));
        create.update();
        r.updateRef(Constants.HEAD).link(Constants.R_HEADS + to);
        RefUpdate delete = r.updateRef(Constants.R_HEADS + from);
        delete.setForceUpdate(true);
        delete.delete();
    }

    /** A commit with only a README on {@code branch}, which becomes the default branch. */
    private static void commitReadme(Repository r, String branch) throws Exception {
        try (ObjectInserter ins = r.newObjectInserter()) {
            TreeFormatter tree = new TreeFormatter();
            tree.append("README.md", FileMode.REGULAR_FILE, ins.insert(Constants.OBJ_BLOB, "# Models\n".getBytes(StandardCharsets.UTF_8)));
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(ins.insert(tree));
            PersonIdent who = new PersonIdent("GitHub", "noreply@github.com");
            cb.setAuthor(who);
            cb.setCommitter(who);
            cb.setMessage("Initial commit");
            ObjectId commit = ins.insert(cb);
            ins.flush();
            RefUpdate u = r.updateRef(Constants.R_HEADS + branch);
            u.setNewObjectId(commit);
            u.update();
        }
        r.updateRef(Constants.HEAD).link(Constants.R_HEADS + branch);
    }

    private SharedModel shareAsAlice() throws Exception {
        Path source = tmp.resolve("source.archimate");
        Files.writeString(source, TestModels.text());
        SharedModel a = SharedModel.share(source, tmp.resolve("alice"), remote);
        SyncResult first = a.sync(alice);
        assertThat(first.committed()).isEqualTo("First version of 'Test Model'");
        assertThat(first.pushed()).isTrue();
        return a;
    }

    private SharedModel cloneAsBob() throws Exception {
        return SharedModel.cloneFrom(remote, tmp.resolve("bob"), bob);
    }

    private static void edit(SharedModel m, String from, String to) throws Exception {
        String text = Files.readString(m.modelFile());
        assertThat(text).contains(from);
        Files.writeString(m.modelFile(), text.replace(from, to));
    }

    /** Model content independent of formatting/order. */
    private static String semantic(SharedModel m) throws Exception {
        ModelGraph g = ModelGraph.read(Files.readAllBytes(m.modelFile()));
        return new String(Xml.write(io.github.p3chys.cooperativearchi.model.SplitLayout.canonical(g).toXml()),
                StandardCharsets.UTF_8);
    }
}
