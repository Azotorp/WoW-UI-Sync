package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.BackupRecord;
import io.github.azotorp.wowuisync.client.local.Backups;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.util.Sizes;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;

/** Backups view (DESIGN.md §18): list, manual backup, restore, delete. */
public final class BackupsDialog extends JDialog {
    private final ClientApp app;
    private final Flavor flavor;
    private final DefaultListModel<BackupRecord> model = new DefaultListModel<>();
    private final JList<BackupRecord> list = new JList<>(model);
    private boolean restored;

    private BackupsDialog(Window owner, ClientApp app, Flavor flavor) {
        super(owner, "Backups - " + flavor.displayName(), ModalityType.APPLICATION_MODAL);
        this.app = app;
        this.flavor = flavor;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, v, i, sel, foc) -> {
            String size = v.sizeBytes() >= 0 ? Sizes.human(v.sizeBytes()) + ", " + v.fileCount() + " files" : "size unknown";
            JLabel lab = new JLabel("<html><b>" + Dialogs.escape(Backups.describe(v)) + "</b><br><small>Interface_" + Dialogs.escape(v.suffix())
                    + " + WTF_" + Dialogs.escape(v.suffix()) + " · " + size + "</small></html>");
            lab.setOpaque(true);
            lab.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            lab.setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            lab.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            return lab;
        });
        JButton manual = new JButton("Manual backup");
        JButton restore = new JButton("Restore…");
        JButton delete = new JButton("Delete…");
        JButton close = new JButton("Close");
        manual.addActionListener(e -> manual());
        restore.addActionListener(e -> restore());
        delete.addActionListener(e -> delete());
        close.addActionListener(e -> dispose());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(manual);
        b.add(restore);
        b.add(delete);
        b.add(close);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(620, 320));
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(new JLabel("<html>Full copies of Interface and WTF kept inside " + Dialogs.escape(flavor.path().toString())
                + ". WoW ignores these folders. Backups are kept until you delete them.</html>"), BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(b, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
        reload();
    }

    private void reload() {
        model.clear();
        try {
            List<BackupRecord> l = Backups.list(app.db(), flavor.path());
            for (BackupRecord r : l) model.addElement(r);
        } catch (Exception e) {
            Dialogs.error(this, e);
        }
    }

    private void manual() {
        BusyDialog.run(this, "Backing up", d -> () -> Backups.create(app.db(), flavor.path(), Backups.REASON_MANUAL, null, d::update),
                r -> {
                    reload();
                    Dialogs.info(this, "Backup created: " + Backups.describe(r));
                });
    }

    private void restore() {
        BackupRecord b = list.getSelectedValue();
        if (b == null) return;
        if (!WowGuard.mayChange(this, flavor)) return;
        if (!Dialogs.confirmWarning(this, "Restore this backup?\n\n" + Backups.describe(b)
                + "\n\nYour current Interface and WTF are backed up first, then made to match this backup.")) return;
        BusyDialog.run(this, "Restoring", d -> () -> {
            Backups.restore(app.db(), flavor.path(), flavor.profileId(), b, d::update);
            return true;
        }, ok -> {
            restored = true;
            reload();
            Dialogs.info(this, "Restored. A backup of the previous state was made (\"Before restore\"); restore it to undo.");
        });
    }

    private void delete() {
        BackupRecord b = list.getSelectedValue();
        if (b == null) return;
        if (!Dialogs.confirmWarning(this, "Delete this backup permanently?\n\n" + Backups.describe(b))) return;
        BusyDialog.run(this, "Deleting backup", d -> () -> {
            Backups.delete(app.db(), flavor.path(), b);
            return true;
        }, ok -> reload());
    }

    /** Returns true if a restore happened (status must be refreshed). */
    public static boolean show(Window owner, ClientApp app, Flavor flavor) {
        BackupsDialog d = new BackupsDialog(owner, app, flavor);
        d.setVisible(true);
        return d.restored;
    }
}
