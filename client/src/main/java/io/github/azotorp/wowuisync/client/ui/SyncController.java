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

    void refreshStatus() {
        Ctx c = ctx();
        if (c == null || app.busy()) return;
        int gen = ++statusGeneration;
        w.showStatus(StatusView.message("Checking…", Theme.GRAY, "Scanning files and asking the server."));
        app.setBusy(true);
        var checking=w.progress.begin("Checking status", false);
        Bg.run(() -> {
            Msg.StatusResp remote=c.conn().call(new Msg.StatusReq(c.account().id(),c.flavor().profileId(),null,null),Msg.StatusResp.class);
            if(remote.currentFingerprint()==null) {
                // No comparison is possible yet. A real Push still performs full validation/hashing.
                return new SyncStatus(SyncStatus.Kind.SERVER_EMPTY,new LocalScan(c.flavor().path(),
                        io.github.azotorp.wowuisync.common.manifest.Manifest.empty(),java.util.Map.of(),List.of(),List.of()),remote,null);
            }
            LocalScan scan = ops.scan(c.flavor(), scanProgress(), checking::cancelled);
            return ops.status(c.conn(), c.account(), c.flavor(), scan);
        }, s -> {
            app.setBusy(false);
            w.progress.idle();
            if (gen != statusGeneration) return;
            last = s;
            w.showStatus(view(s, c.account()));
            if (!s.scan().skippedInvalid().isEmpty()) {
                w.progress.note(s.scan().skippedInvalid().size() + " files with names Windows cannot use are skipped.");
            }
        }, e -> {
            app.setBusy(false);
            w.progress.idle();
            w.showStatus(StatusView.message("Status unavailable", Theme.GRAY, Dialogs.message(e)));
            if (app.connection() != null && app.connection().isClosed()) {
                app.disconnect();
                Dialogs.error(w, "The connection to the server was lost. Connect again.");
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
            case SETTINGS_DIFFER -> new StatusView("Add-ons and data match; general WTF settings differ", Theme.BLUE,
                    lastPush + " · General WTF settings: opt-in", true);
            case LOCAL_CHANGES -> new StatusView("Local changes - push recommended", Theme.BLUE, lastPush + " · " + local, true);
            case SERVER_NEWER -> new StatusView("Server is newer (from " + r.lastPushDevice() + ", " + StatusView.when(r.lastPushAt())
                    + ") - pull recommended", Theme.ORANGE, lastPush, true);
            case CONFLICT -> new StatusView("Conflict", Theme.RED, "Both this PC and the server changed since the last sync. "
                    + lastPush, true);
            case DIFFERENT -> new StatusView("Different from the server", Theme.GRAY,
                    "This PC has not synced with " + account.username() + " yet. " + lastPush, true);
            case SERVER_EMPTY -> new StatusView("Nothing stored on the server yet - push to create the first copy", Theme.BLUE, "Content hashing is deferred until Push; no local/server comparison is needed yet.", false);
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
            LocalScan scan = ops.scan(c.flavor(), scanProgress(), scanCancellation(control));
            if (!scan.caseCollisions().isEmpty()) {
                StringBuilder sb = new StringBuilder("These files differ only by upper/lower case. Rename or remove one of each, then push again:\n");
                for (List<String> g : scan.caseCollisions()) sb.append("\n").append(String.join("  /  ", g));
                throw new UserException(sb.toString());
            }
            if (scan.manifest().size()==0) throw new UserException("Push blocked: this installation has no syncable files.");
            return ops.status(c.conn(), c.account(), c.flavor(), scan);
        }, s -> pushSelect(c, s, control), e -> fail(e, "Push"));
    }

    private void pushSelect(Ctx c, SyncStatus s, io.github.azotorp.wowuisync.client.sync.TransferControl control) {
        Bg.run(() -> new Object[] {ops.pullSource(c.conn(),c.account(),c.flavor()),SyncOps.localSource(s.scan(),c.flavor().profileId())}, values -> {
            SyncOps.PullSource remote=(SyncOps.PullSource)values[0],local=(SyncOps.PullSource)values[1];
            var serverScan=new LocalScan(s.scan().flavorPath(),remote.manifest(),java.util.Map.of(),List.of(),List.of());
            var ch=PullSelectionDialog.show(w,c.account().username(),local,serverScan,PullSelectionDialog.parse(remote.tocs()),true);
            if(ch==null){done("Push cancelled.");return;}
            var target=io.github.azotorp.wowuisync.common.addon.PullPlanner.target(s.scan().manifest(),remote.manifest(),ch.selection(),
                    PullSelectionDialog.parse(local.tocs()),PullSelectionDialog.parse(remote.tocs()));
            var selected=new LocalScan(s.scan().flavorPath(),target,s.scan().files(),s.scan().skippedInvalid(),s.scan().caseCollisions());
            Msg.StatusResp info=s.server();
            var pinned=new Msg.StatusResp(remote.fingerprint(),info.lastPushAt(),info.lastPushDevice(),info.lastPushBy(),info.storedBytes(),info.quotaMb(),info.accountUsedBytes());
            pushConfirm(c,new SyncStatus(s.kind(),selected,pinned,s.base()),control);
        },e->fail(e,"Push"));
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
            Dialogs.info(w, "Nothing to push: the selected content already matches the server.\n\nUnselected content can still differ. Use Show details to inspect differences; general WTF settings are unchecked by default.");
            refreshStatus();
            return;
        }
        boolean own = isOwnAccount(c.account());
        String headline = own
                ? "Push to " + c.account().username() + " (" + c.flavor().displayName() + "): selected content updates the server; unrelated content is preserved."
                : "You are pushing to " + c.account().username() + "'s setup, not your own. Their server copy (" + c.flavor().displayName()
                + ") will receive the selected changes.";
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
            Dialogs.info(w, "Push complete. The server copy of " + c.account().username() + " now contains the reviewed changes.");
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
            LocalScan scan = ops.scan(c.flavor(), scanProgress(), scanCancellation(control));
            scanRef.set(scan);
            w.progress.phase("Reading " + c.account().username() + "'s addon list…");
            return new Object[] {ops.pullSource(c.conn(), c.account(), c.flavor()), SyncOps.localTocs(scan,c.flavor().profileId()),
                    io.github.azotorp.wowuisync.client.local.FileOps.size(scan.flavorPath().resolve("Interface")).bytes(),
                    io.github.azotorp.wowuisync.client.local.FileOps.size(scan.flavorPath().resolve("WTF")).bytes()};
        }, values -> {
            SyncOps.PullSource src=(SyncOps.PullSource)values[0];
            @SuppressWarnings("unchecked") var localTocs=(java.util.Map<String,io.github.azotorp.wowuisync.common.addon.TocInfo>)values[1];
            if(src.fingerprint()==null){done("Nothing stored on server.");Dialogs.info(w,"This account has no server copy for the selected version.");return;}
            w.progress.phase("Choose what to pull…");
            PullSelectionDialog.Choice ch = PullSelectionDialog.show(w, c.account().username(), src, scanRef.get(),localTocs,false,(Long)values[2],(Long)values[3]);
            if (ch == null) {
                done("Pull cancelled.");
                return;
            }
            w.progress.phase("Planning…");
            Bg.run(() -> ops.pullPlan(src, scanRef.get(), ch.selection(), c.flavor().profileId()),
                    plan -> pullReview(c, scanRef.get(), plan, control, ch.backupInterface(), ch.backupWtf()), e -> fail(e, "Pull"));
        }, e -> fail(e, "Pull"));
    }

    private void pullReview(Ctx c, LocalScan scan, Msg.PullPlan plan, io.github.azotorp.wowuisync.client.sync.TransferControl control, boolean backupInterface, boolean backupWtf) {
        if (plan.newFiles().isEmpty() && plan.editedFiles().isEmpty() && plan.removedFiles().isEmpty()) {
            done("Nothing to pull.");
            Dialogs.info(w, "Nothing to change: this PC already matches your selection.");
            return;
        }
        String headline = "Pull from " + c.account().username() + " into " + c.flavor().displayName()
                + ". These changes will be made on this PC; only the chosen backup categories are copied first.";
        String footer = "Download: up to " + Sizes.human(plan.totalBytes()) + " (files already on this PC are reused).";
        if (!ReviewDialog.show(w, "Review pull", headline, paths(plan.newFiles()), paths(plan.editedFiles()), plan.removedFiles(), footer, null)) {
            done("Pull cancelled.");
            return;
        }
        if (!WowGuard.mayChange(w, c.flavor())) {
            done("Pull cancelled.");
            return;
        }
        w.progress.phase("Backing up, then downloading…");
        Bg.run(() -> ops.pullApply(c.conn(), c.account(), c.flavor(), scan, plan, control, sink(),
                new io.github.azotorp.wowuisync.client.local.Backups.Progress() {
                    public void update(String phase,long done,long total){w.progress.fileOperation(phase,"",done,total,0,-1);}
                    public void file(String phase,String path,long done,long total,long bytes,long totalBytes){w.progress.fileOperation(phase,path,done,total,bytes,totalBytes);}
                }, backupInterface, backupWtf), addonsChanged -> {
            done("Pull complete.");
            app.fire();
            String msg = "Pull complete." + (backupInterface || backupWtf ? " Selected backup categories are listed under Backups." : " No backup was requested.");
            if (addonsChanged) {
                msg += "\n\nIf you use CurseForge, WowUp, or a similar addon manager, run a rescan or refresh so it picks up the synced addons.";
            }
            Dialogs.info(w, msg);
            refreshStatus();
        }, e -> fail(e, "Pull"));
    }

    // ------------------------------------------------------------------ helpers

    private io.github.azotorp.wowuisync.client.sync.Scanner.Progress scanProgress() {
        return new io.github.azotorp.wowuisync.client.sync.Scanner.Progress() {
            public void update(int done,int total){w.progress.count("Scanning files",done,total);}
            public void metrics(int hits,int hashed,long read,double elapsed,double eta,int workers){w.progress.scanMetrics(hits,hashed,read,elapsed,eta,workers);}
            public void currentFile(String path){w.progress.currentFile(path);}
        };
    }
    private java.util.function.BooleanSupplier scanCancellation(io.github.azotorp.wowuisync.client.sync.TransferControl control) {
        return () -> {try {control.checkpoint();return false;}catch(CancelledException e){return true;}};
    }
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

        @Override public void current(String description) { p.currentFile(description); }
        @Override
        public void connectionLost(String message) {
            p.pauseFromEngine(message);
        }
    }
}
