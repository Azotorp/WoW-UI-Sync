package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.local.AddonManager;
import io.github.azotorp.wowuisync.client.local.Backups;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.util.Sizes;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/** Local addon manager (DESIGN.md §17.4). */
public final class AddonsDialog extends JDialog {
    private final ClientApp app;
    private final Flavor flavor;
    private final List<AddonManager.LocalAddon> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");
    private boolean changed;

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Addon", "Folders", "Size", "Data", "Data size"};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            AddonManager.LocalAddon a = rows.get(r);
            return switch (c) {
                case 0 -> a.group().displayName();
                case 1 -> String.join(", ", a.group().folders());
                case 2 -> Sizes.human(a.addonBytes());
                case 3 -> a.dataFiles().size() + " files";
                default -> Sizes.human(a.dataBytes());
            };
        }
    }

    private AddonsDialog(Window owner, ClientApp app, Flavor flavor) {
        super(owner, "Addons - " + flavor.displayName(), ModalityType.APPLICATION_MODAL);
        this.app = app;
        this.flavor = flavor;
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        JButton delAddon = new JButton("Delete addon…");
        JButton delData = new JButton("Delete data…");
        JButton close = new JButton("Close");
        delAddon.addActionListener(e -> delete(true));
        delData.addActionListener(e -> delete(false));
        close.addActionListener(e -> dispose());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(delAddon);
        b.add(delData);
        b.add(close);
        JPanel south = new JPanel(new BorderLayout());
        south.add(summary, BorderLayout.WEST);
        south.add(b, BorderLayout.EAST);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(820, 420));
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(new JLabel("Addons in this flavor. Data = SavedVariables across all characters. A server recovery snapshot is required before deleting."),
                BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private void load() {
        BusyDialog.run(this, "Reading addons", d -> () -> AddonManager.list(flavor.path()), l -> {
            rows.clear();
            rows.addAll(l);
            model.fireTableDataChanged();
            long total = 0, data = 0;
            for (AddonManager.LocalAddon a : l) {
                total += a.addonBytes();
                data += a.dataBytes();
            }
            summary.setText(l.size() + " addons · " + Sizes.human(total) + " · data " + Sizes.human(data));
        });
    }

    private void delete(boolean addon) {
        int v = table.getSelectedRow();
        if (v < 0) return;
        AddonManager.LocalAddon a = rows.get(table.convertRowIndexToModel(v));
        if (!addon && a.dataFiles().isEmpty()) {
            Dialogs.info(this, a.group().displayName() + " has no saved data.");
            return;
        }
        if (!WowGuard.mayChange(this, flavor)) return;
        String what = addon ? "Delete the addon " + a.group().displayName() + " (folders: " + String.join(", ", a.group().folders()) + ")?"
                : "Delete the saved data of " + a.group().displayName() + " (" + a.dataFiles().size() + " files across all characters)?";
        if (!Dialogs.confirmWarning(this, what + "\n\nA server recovery snapshot is confirmed first.")) return;
        BusyDialog.run(this, addon ? "Deleting addon" : "Deleting data", d -> () -> {
            if(!app.connected())throw new io.github.azotorp.wowuisync.client.util.UserException("Connect before deleting so a recovery snapshot can be created.");
            var ops=new io.github.azotorp.wowuisync.client.sync.SyncOps(app);var scan=ops.scan(flavor,null,()->false);
            java.util.Set<String> folders=new java.util.HashSet<>();for(String folder:a.group().folders())folders.add(folder.toLowerCase(java.util.Locale.ROOT));
            java.util.Set<String> data=new java.util.HashSet<>();for(var path:a.dataFiles())data.add(flavor.path().relativize(path).toString().replace('\\','/').toLowerCase(java.util.Locale.ROOT));
            java.util.List<String> removals=new java.util.ArrayList<>();
            for(var entry:scan.manifest().entries()) {
                String folder=io.github.azotorp.wowuisync.common.path.SyncPaths.addonFolder(entry.path());
                if(addon ? folder!=null && folders.contains(folder.toLowerCase(java.util.Locale.ROOT)):data.contains(entry.path().toLowerCase(java.util.Locale.ROOT)))removals.add(entry.path());
            }
            var plan=new io.github.azotorp.wowuisync.common.protocol.Msg.PullPlan(java.util.List.of(),java.util.List.of(),removals,0,null,false);
            ops.apply(app.connection(),app.targetAccount(),flavor,scan,plan,new io.github.azotorp.wowuisync.client.sync.TransferControl(),new io.github.azotorp.wowuisync.client.sync.TransferEngine.Sink(){
                public void stats(io.github.azotorp.wowuisync.client.sync.TransferStats s){d.update("Saving recovery snapshot",s.bytesDone.get(),s.bytesTotal);}
                public void connectionLost(String m){throw new IllegalStateException(m);}
            },"before_pull_recovery");
            return true;
        }, ok -> {
            changed = true;
            load();
        });
    }

    public static boolean show(Window owner, ClientApp app, Flavor flavor) {
        AddonsDialog d = new AddonsDialog(owner, app, flavor);
        javax.swing.SwingUtilities.invokeLater(d::load);
        d.setVisible(true);
        return d.changed;
    }
}
