package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.sync.LocalScan;
import io.github.azotorp.wowuisync.client.sync.SyncOps;
import io.github.azotorp.wowuisync.client.sync.SyncStatus;
import io.github.azotorp.wowuisync.client.sync.TransferEngine;
import io.github.azotorp.wowuisync.client.sync.TransferStats;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.util.UserException;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.util.Sizes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Status, push and pull as seen from the main window (DESIGN.md §11.3, §11.4, §12). */
final class SyncController {
    private final MainWindow w;
    private final ClientApp app;
    private final SyncOps ops;
    private volatile SyncStatus last;
    private int statusGeneration;

    SyncController(MainWindow w, ClientApp app) {
        this.w = w;
        this.app = app;
        this.ops = new SyncOps(app);
    }

    private record Ctx(ServerConnection conn, Msg.AccountInfo account, Flavor flavor) {}

    private Ctx ctx() {
        if (!app.connected() || app.flavor() == null || app.targetAccount() == null) return null;
        return new Ctx(app.connection(), app.targetAccount(), app.flavor());
    }

    private boolean isOwnAccount(Msg.AccountInfo a) {
        return app.login() != null && a.id().equals(app.login().accountId());
    }

    // ------------------------------------------------------------------ status

    private io.github.azotorp.wowuisync.client.sync.Scanner.Progress scanProgress() {
        return new io.github.azotorp.wowuisync.client.sync.Scanner.Progress() {
            public void update(int d,int t) { w.progress.count(t==0?"Files discovered":"Files checked",Math.abs(d),t); }
            public void phase(String text) { w.progress.phase(text); }
            public void statistics(io.github.azotorp.wowuisync.client.sync.Scanner.Statistics s) {
                w.progress.note("Cache hits: "+s.cacheHits()+" · Files hashed: "+s.hashedFiles()+" · "+Sizes.human(s.bytesHashed())+" read · "+String.format(java.util.Locale.ROOT,"%.1f s",s.elapsedNanos()/1e9));
            }
        };
    }
    void refreshStatus() {
        Ctx c = ctx();
        if (c == null || app.busy()) return;
        int gen = ++statusGeneration;
        last=null;
        w.showStatus(StatusView.message("Checking…", Theme.GRAY, "Discovering local files."));
        app.setBusy(true);
        ProgressPanel.Control statusControl = w.progress.begin("Checking status", false);
        Bg.run(() -> {
            LocalScan scan = ops.statusScan(c.flavor(), scanProgress(), statusControl::cancelled);
            statusControl.checkpoint();
            w.progress.phase("Asking server for status");
            long networkStart=System.nanoTime();
            statusControl.onCancel(()->Thread.ofVirtual().start(c.conn()::close));
            SyncStatus result;
            try { result=ops.status(c.conn(),c.account(),c.flavor(),scan); }
            catch(Exception e) { statusControl.checkpoint();throw e; }
            finally { statusControl.onCancel(null); }
            statusControl.checkpoint();
            io.github.azotorp.wowuisync.client.util.ClientLog.LOG.info("Status request count=1 elapsedMs="+(System.nanoTime()-networkStart)/1e6);
            return result;
        }, s -> {
            app.setBusy(false);
            w.progress.idle();
            if (gen != statusGeneration || ctx()==null || !c.equals(ctx()) || statusControl.cancelled()) return;
            last = s;
            w.showStatus(view(s, c.account()));
            if (!s.scan().skippedInvalid().isEmpty()) {
                w.progress.note(s.scan().skippedInvalid().size() + " files with names Windows cannot use are skipped.");
            }
        }, e -> {
            app.setBusy(false);
            w.progress.idle();
            if (gen != statusGeneration || app.connection()!=c.conn() || !java.util.Objects.equals(app.flavor(),c.flavor()) || !java.util.Objects.equals(app.targetAccount(),c.account())) return;
            w.showStatus(StatusView.message(e instanceof CancelledException ? "Status check cancelled" : "Status unavailable", Theme.GRAY, Dialogs.message(e)));
            if (app.connection() != null && app.connection().isClosed()) {
                app.disconnect();
                if(!statusControl.cancelled()) Dialogs.error(w, "The connection to the server was lost. Connect again.");
            }
        });
    }

    private StatusView view(SyncStatus s, Msg.AccountInfo account) {
        Msg.StatusResp r = s.server();
        String lastPush = r.lastPushAt() == null ? "" : "Last push: " + r.lastPushDevice() + ", " + StatusView.when(r.lastPushAt())
                + (r.lastPushBy() != null && !r.lastPushBy().equals(account.username()) ? " (by " + r.lastPushBy() + ")" : "")
                + " · stored " + Sizes.human(r.storedBytes());
        String local = s.scan().manifest().size() + " files (" + Sizes.human(s.scan().manifest().totalBytes()) + ") on this PC";
        return switch (s.kind()) {
            case UP_TO_DATE -> new StatusView("Up to date", Theme.GREEN, lastPush + " · " + local, false);
            case LOCAL_CHANGES -> new StatusView("Local changes - push recommended", Theme.BLUE, lastPush + " · " + local, true);
            case SERVER_NEWER -> new StatusView("Server is newer (from " + r.lastPushDevice() + ", " + StatusView.when(r.lastPushAt())
                    + ") - pull recommended", Theme.ORANGE, lastPush, true);
            case CONFLICT -> new StatusView("Conflict", Theme.RED, "Both this PC and the server changed since the last sync. "
                    + lastPush, true);
            case DIFFERENT -> new StatusView("Different from the server", Theme.GRAY,
                    "This PC has not synced with " + account.username() + " yet. " + lastPush, true);
            case SERVER_EMPTY -> new StatusView("Nothing stored on the server yet - push to create the first copy", Theme.BLUE, local, false);
        };
    }

    void showDetails() {
        Ctx c = ctx();
        SyncStatus s = last;
        if (c == null || s == null) return;
        BusyDialog.run(w, "Comparing with the server", d -> () -> ops.serverDiff(c.conn(), c.account(), c.flavor(), s.scan()), diff -> {
            List<String> onlyServer = paths(diff.added());
            List<String> changed = paths(diff.edited());
            List<String> onlyLocal = paths(diff.removed());
            ReviewDialog.info(w, "Details", "Differences between this PC and " + c.account().username() + "'s server copy.",
                    new String[] {"Only on the server", "Different on each side", "Only on this PC"}, onlyServer, changed, onlyLocal);
        });
    }

    private static List<String> paths(List<ManifestEntry> l) {
        List<String> out = new ArrayList<>(l.size());
        for (ManifestEntry e : l) out.add(e.path());
        return out;
    }

    // ------------------------------------------------------------------ push

    void push() {
        Ctx c = ctx();
        if (c == null || app.busy()) return;
        if (!WowGuard.mayPush(w, c.flavor())) return;
        app.setBusy(true);
        var control = w.progress.begin("Push to " + c.account().username(), true);
        Bg.run(() -> {
            LocalScan scan = ops.scan(c.flavor(), scanProgress(), control::cancelled);
            if (!scan.caseCollisions().isEmpty()) {
                StringBuilder sb = new StringBuilder("These files differ only by upper/lower case. Rename or remove one of each, then push again:\n");
                for (List<String> g : scan.caseCollisions()) sb.append("\n").append(String.join("  /  ", g));
                throw new UserException(sb.toString());
            }
            return ops.status(c.conn(), c.account(), c.flavor(), scan);
        }, s -> pushConfirm(c, s, control), e -> fail(e, "Push"));
    }

    private void pushConfirm(Ctx c, SyncStatus s, io.github.azotorp.wowuisync.client.sync.TransferControl control) {
        if (!s.scan().skippedInvalid().isEmpty()) {
            Dialogs.warn(w, "These files are skipped because their names are not valid on Windows:\n\n"
                    + String.join("\n", s.scan().skippedInvalid().subList(0, Math.min(40, s.scan().skippedInvalid().size()))));
        }
        Msg.StatusResp r = s.server();
        String cur = r.currentFingerprint();
        String local = s.scan().manifest().fingerprint();
        if (cur != null && !cur.equals(s.base()) && !cur.equals(local)) {
            String from = r.lastPushAt() == null ? "another device" : r.lastPushDevice() + " at " + StatusView.when(r.lastPushAt());
            if (!Dialogs.confirmWarning(w, "The server has changes from " + from + ". Pushing will replace them. Continue?")) {
                done("Push cancelled.");
                return;
            }
        }
        w.progress.phase("Comparing with the server…");
        Bg.run(() -> ops.pushBegin(c.conn(), c.account(), c.flavor(), s.scan(), cur), need -> pushReview(c, s, need, control),
                e -> fail(e, "Push"));
    }

    private void pushReview(Ctx c, SyncStatus s, Msg.PushNeed need, io.github.azotorp.wowuisync.client.sync.TransferControl control) {
        boolean nothing = need.newFiles().isEmpty() && need.editedFiles().isEmpty() && need.removedFiles().isEmpty();
        if (nothing && need.missingHashes().isEmpty() && s.server().currentFingerprint() != null) {
            ops.pushCancel(c.conn(), need);
            done("Nothing to push.");
            Dialogs.info(w, "Nothing to push: the server copy already matches this PC.");
            refreshStatus();
            return;
        }
        boolean own = isOwnAccount(c.account());
        String headline = own
                ? "Push to " + c.account().username() + " (" + c.flavor().displayName() + "): the server copy will become an exact copy of this PC."
                : "You are pushing to " + c.account().username() + "'s setup, not your own. Their server copy (" + c.flavor().displayName()
                + ") will become an exact copy of this PC.";
        String footer = "Upload: " + Sizes.human(need.uploadBytes()) + " (" + need.missingHashes().size() + " files the server does not have yet)."
                + (s.server().currentFingerprint() == null ? " First sync uploads everything. Later syncs only send changes." : "");
        if (!ReviewDialog.show(w, "Review push", headline, need.newFiles(), need.editedFiles(), need.removedFiles(), footer,
                own ? null : c.account().username())) {
            ops.pushCancel(c.conn(), need);
            done("Push cancelled.");
            return;
        }
        if (s.server().currentFingerprint() == null) w.progress.note("First sync uploads everything. Later syncs only send changes.");
        w.progress.phase("Uploading to " + c.account().username() + "…");
        Bg.run(() -> ops.pushUpload(c.conn(), c.account(), c.flavor(), s.scan(), need, control, sink()), pd -> {
            done("Push complete.");
            Dialogs.info(w, "Push complete. The server copy of " + c.account().username() + " now matches this PC.");
            refreshStatus();
        }, e -> {
            ops.pushCancel(c.conn(), need);
            fail(e, "Push");
        });
    }

    // ------------------------------------------------------------------ pull

    void pull() {
        Ctx c = ctx();
        if (c == null || app.busy()) return;
        if (!WowGuard.mayChange(w, c.flavor())) return;
        app.setBusy(true);
        var control = w.progress.begin("Pull from " + c.account().username(), true);
        AtomicReference<LocalScan> scanRef = new AtomicReference<>();
        Bg.run(() -> {
            LocalScan scan = ops.scan(c.flavor(), scanProgress(), control::cancelled);
            scanRef.set(scan);
            w.progress.phase("Reading " + c.account().username() + "'s addon list…");
            return ops.pullSource(c.conn(), c.account(), c.flavor());
        }, src -> {
            w.progress.phase("Choose what to pull…");
            PullSelectionDialog.Choice ch = PullSelectionDialog.show(w, c.account().username(), src, scanRef.get());
            if (ch == null) {
                done("Pull cancelled.");
                return;
            }
            w.progress.phase("Planning…");
            Bg.run(() -> ops.pullPlan(c.conn(), c.account(), c.flavor(), scanRef.get(), ch.selection(), ch.mode()),
                    plan -> pullReview(c, scanRef.get(), plan, control), e -> fail(e, "Pull"));
        }, e -> fail(e, "Pull"));
    }

    private void pullReview(Ctx c, LocalScan scan, Msg.PullPlan plan, io.github.azotorp.wowuisync.client.sync.TransferControl control) {
        if (plan.newFiles().isEmpty() && plan.editedFiles().isEmpty() && plan.removedFiles().isEmpty()) {
            done("Nothing to pull.");
            Dialogs.info(w, "Nothing to change: this PC already matches your selection.");
            return;
        }
        String headline = "Pull from " + c.account().username() + " into " + c.flavor().displayName()
                + ". These changes will be made on this PC; a server recovery snapshot is confirmed first.";
        String footer = "Download: up to " + Sizes.human(plan.totalBytes()) + " (files already on this PC are reused).";
        if (!ReviewDialog.show(w, "Review pull", headline, paths(plan.newFiles()), paths(plan.editedFiles()), plan.removedFiles(), footer, null)) {
            done("Pull cancelled.");
            return;
        }
        if (!WowGuard.mayChange(w, c.flavor())) {
            done("Pull cancelled.");
            return;
        }
        w.progress.phase("Saving recovery snapshot, then downloading…");
        Bg.run(() -> ops.pullApply(c.conn(), c.account(), c.flavor(), scan, plan, control, sink(),
                (what, f, t) -> {
                    if (t > 0) w.progress.count(what, f, t);
                    else w.progress.phase(what + "…");
                }), addonsChanged -> {
            done("Pull complete.");
            app.fire();
            String msg = "Pull complete. Your previous setup is saved as a recovery snapshot in your own account.";
            if (addonsChanged) {
                msg += "\n\nIf you use CurseForge, WowUp, or a similar addon manager, run a rescan or refresh so it picks up the synced addons.";
            }
            Dialogs.info(w, msg);
            refreshStatus();
        }, e -> fail(e, "Pull"));
    }

    // ------------------------------------------------------------------ helpers

    private TransferEngine.Sink sink() {
        return new ProgressPanelSink(w.progress);
    }

    private void done(String text) {
        w.progress.done(text);
        app.setBusy(false);
    }

    private void fail(Throwable e, String what) {
        if (e instanceof CancelledException) {
            done(what + " cancelled. Files already transferred will not be sent again.");
            return;
        }
        done(what + " failed.");
        Dialogs.error(w, e);
        if (app.connection() != null && app.connection().isClosed()) app.disconnect();
    }

    /** Sends engine progress to the panel. */
    static final class ProgressPanelSink implements TransferEngine.Sink {
        private final ProgressPanel p;

        ProgressPanelSink(ProgressPanel p) {
            this.p = p;
        }

        @Override
        public void stats(TransferStats s) {
            p.transfer(s.filesDone.get(), s.filesTotal, s.bytesDone.get(), s.bytesTotal, s.wireBytes.get(), s.bytesPerSecond());
        }

        @Override
        public void connectionLost(String message) {
            p.pauseFromEngine(message);
        }
    }
}
