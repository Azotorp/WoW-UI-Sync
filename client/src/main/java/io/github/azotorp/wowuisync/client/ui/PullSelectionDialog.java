package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.sync.LocalScan;
import io.github.azotorp.wowuisync.client.sync.SyncOps;
import io.github.azotorp.wowuisync.common.addon.AddonGroup;
import io.github.azotorp.wowuisync.common.addon.AddonGrouper;
import io.github.azotorp.wowuisync.common.addon.DataMapper;
import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullPlanner;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.addon.TocInfo;
import io.github.azotorp.wowuisync.common.addon.TocParser;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import io.github.azotorp.wowuisync.common.util.Sizes;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Selection window for a selective pull (DESIGN.md §12.1). */
public final class PullSelectionDialog extends JDialog {
    /** The user's choice. */
    public record Choice(PullSelection selection, PullMode mode) {}

    private final class Row {
        final AddonGroup group;
        boolean addon = true;
        boolean data = true;
        long size;
        long dataSize;

        Row(AddonGroup g) {
            this.group = g;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final JCheckBox general = new JCheckBox("General settings (macros, key bindings, chat and layout, Blizzard settings)", true);
    private final JCheckBox keep = new JCheckBox("Keep my other addons (do not remove addons that are not selected)");
    private final JLabel size = new JLabel(" ");
    private final SyncOps.PullSource source;
    private final LocalScan local;
    private final Model model = new Model();
    private Choice choice;

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Addon", "Include data", "Name", "Folders", "Size", "Data"};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c < 2 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c < 2; }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.addon;
                case 1 -> row.data;
                case 2 -> row.group.displayName();
                case 3 -> String.join(", ", row.group.folders());
                case 4 -> Sizes.human(row.size);
                default -> row.dataSize == 0 ? "-" : Sizes.human(row.dataSize);
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            Row row = rows.get(r);
            if (c == 0) row.addon = (Boolean) v;
            if (c == 1) row.data = (Boolean) v;
            fireTableRowsUpdated(r, r);
            updateSize();
        }
    }

    private PullSelectionDialog(Window owner, String sourceName, SyncOps.PullSource source, LocalScan local) {
        super(owner, "Pull from " + sourceName, ModalityType.APPLICATION_MODAL);
        this.source = source;
        this.local = local;
        Map<String, TocInfo> tocs = new HashMap<>();
        for (Map.Entry<String, byte[]> e : source.tocs().entrySet()) tocs.put(e.getKey(), TocParser.parse(e.getKey(), e.getValue()));
        List<String> folders = AddonGrouper.folders(source.manifest());
        Set<String> known = new HashSet<>();
        for (String f : folders) known.add(f.toLowerCase(Locale.ROOT));
        for (String f : AddonGrouper.folders(local.manifest())) known.add(f.toLowerCase(Locale.ROOT));
        Map<String, Long> folderSize = new HashMap<>();
        Map<String, Long> dataSize = new HashMap<>();
        for (ManifestEntry e : source.manifest().entries()) {
            DataMapper.Classification c = DataMapper.classify(e.path(), known);
            if (c.kind() == DataMapper.Kind.ADDON_FILE) folderSize.merge(c.folder().toLowerCase(Locale.ROOT), e.size(), Long::sum);
            if (c.kind() == DataMapper.Kind.ADDON_DATA) dataSize.merge(c.folder().toLowerCase(Locale.ROOT), e.size(), Long::sum);
        }
        for (AddonGroup g : AddonGrouper.group(folders, tocs)) {
            Row r = new Row(g);
            for (String f : g.folders()) {
                r.size += folderSize.getOrDefault(f.toLowerCase(Locale.ROOT), 0L);
                r.dataSize += dataSize.getOrDefault(f.toLowerCase(Locale.ROOT), 0L);
            }
            rows.add(r);
        }
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(1).setMaxWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(860, 380));

        JButton allAddons = new JButton("All addons");
        JButton allData = new JButton("All data");
        JButton none = new JButton("None");
        allAddons.addActionListener(e -> setAll(true, null));
        allData.addActionListener(e -> setAll(null, true));
        none.addActionListener(e -> {
            setAll(false, false);
            general.setSelected(false);
            updateSize();
        });
        general.addActionListener(e -> updateSize());
        keep.addActionListener(e -> updateSize());
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        quick.add(allAddons);
        quick.add(allData);
        quick.add(none);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(new JLabel("<html>Choose what to pull from <b>" + Dialogs.escape(sourceName) + "</b>. 'Include data' copies the addon's "
                + "saved settings (SavedVariables) for all characters. Addons without data ticked keep your own data.</html>"), BorderLayout.NORTH);
        top.add(general, BorderLayout.CENTER);
        top.add(quick, BorderLayout.SOUTH);

        JButton ok = new JButton("Continue");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            choice = new Choice(selection(), keep.isSelected() ? PullMode.KEEP : PullMode.REPLACE);
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(keep, BorderLayout.NORTH);
        south.add(size, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(top, BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
        updateSize();
    }

    private void setAll(Boolean addon, Boolean data) {
        for (Row r : rows) {
            if (addon != null) r.addon = addon;
            if (data != null) r.data = data;
        }
        model.fireTableDataChanged();
        updateSize();
    }

    private PullSelection selection() {
        List<String> a = new ArrayList<>();
        List<String> d = new ArrayList<>();
        for (Row r : rows) {
            if (r.addon) a.addAll(r.group.folders());
            if (r.data) d.addAll(r.group.folders());
        }
        return new PullSelection(a, d, general.isSelected());
    }

    private void updateSize() {
        PullMode mode = keep.isSelected() ? PullMode.KEEP : PullMode.REPLACE;
        ManifestDiff d = PullPlanner.plan(source.manifest(), local.manifest(), selection(), mode);
        Set<String> seen = new HashSet<>();
        Set<String> localHashes = new HashSet<>();
        for (ManifestEntry e : local.manifest().entries()) localHashes.add(e.sha256());
        long bytes = 0;
        for (List<ManifestEntry> l : List.of(d.added(), d.edited())) {
            for (ManifestEntry e : l) if (!localHashes.contains(e.sha256()) && seen.add(e.sha256())) bytes += e.size();
        }
        int removedAddons = 0;
        for (ManifestEntry e : d.removed()) if (SyncPaths.addonFolder(e.path()) != null) removedAddons++;
        size.setText("Download: " + Sizes.human(bytes) + " · " + (d.added().size() + d.edited().size()) + " files to write, "
                + d.removed().size() + " to remove" + (removedAddons > 0 && mode == PullMode.REPLACE ? " (including addon files)" : ""));
    }

    public static Choice show(Window owner, String sourceName, SyncOps.PullSource source, LocalScan local) {
        PullSelectionDialog d = new PullSelectionDialog(owner, sourceName, source, local);
        d.setVisible(true);
        return d.choice;
    }
}
