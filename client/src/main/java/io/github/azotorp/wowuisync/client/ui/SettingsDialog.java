package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.client.wow.FlavorDetector;
import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.path.Exclusions;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Settings (DESIGN.md §17.3): nickname, install root and flavors, exclusions, data connections, theme. */
public final class SettingsDialog extends JDialog {
    private final ClientApp app;
    private final JTextField nickname = new JTextField(24);
    private final JTextField root = new JTextField(28);
    private final DefaultListModel<Flavor> flavors = new DefaultListModel<>();
    private final JTextArea exclusions = new JTextArea(9, 40);
    private final JSpinner dataConns = new JSpinner(new SpinnerNumberModel(Defaults.DATA_CONNECTIONS, 1, Defaults.MAX_DATA_CONNECTIONS, 1));
    private final JComboBox<String> theme = new JComboBox<>(new String[] {"dark", "light"});
    private boolean changed;

    public SettingsDialog(Window owner, ClientApp app) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        this.app = app;
        nickname.setText(app.config().deviceNickname);
        root.setText(app.config().installRoot == null ? "" : app.config().installRoot);
        dataConns.setValue(app.config().dataConnections);
        theme.setSelectedItem(app.config().theme);
        String profile = app.flavor() != null ? app.flavor().profileId() : "forever";
        exclusions.setText(String.join("\n", app.config().profile(profile).exclusions));
        exclusions.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, exclusions.getFont().getSize()));

        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(root.getText().isBlank() ? null : new File(root.getText()));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Choose your World of Warcraft folder (it contains _forever_ and similar folders)");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                root.setText(fc.getSelectedFile().getAbsolutePath());
                detect();
            }
        });
        JButton detect = new JButton("Detect");
        detect.addActionListener(e -> detect());
        JPanel rootRow = new JPanel(new BorderLayout(6, 0));
        rootRow.add(root, BorderLayout.CENTER);
        JPanel rb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rb.add(browse);
        rb.add(detect);
        rootRow.add(rb, BorderLayout.EAST);
        JList<Flavor> fl = new JList<>(flavors);
        fl.setVisibleRowCount(4);
        JScrollPane flScroll = new JScrollPane(fl);
        flScroll.setPreferredSize(new Dimension(420, 80));
        JButton resetEx = new JButton("Restore defaults");
        resetEx.addActionListener(e -> exclusions.setText(String.join("\n", Exclusions.DEFAULTS)));
        JPanel exPanel = new JPanel(new BorderLayout(0, 4));
        exPanel.add(new JScrollPane(exclusions), BorderLayout.CENTER);
        JPanel exb = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        exb.add(resetEx);
        exPanel.add(exb, BorderLayout.SOUTH);

        Forms f = new Forms()
                .add("Device nickname:", nickname)
                .add("WoW folder:", rootRow)
                .add("Detected flavors:", flScroll)
                .add("Exclusions (" + profile + "):", exPanel)
                .add("Data connections:", dataConns)
                .add("Theme:", theme);
        if (root.getText().isBlank() && FlavorDetector.suggestedRoot() != null) root.setText(FlavorDetector.suggestedRoot().toString());
        detect();

        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> save(profile));
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        JPanel p = new JPanel(new BorderLayout());
        p.add(f.panel(), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setContentPane(p);
        pack();
        setLocationRelativeTo(owner);
    }

    private void detect() {
        flavors.clear();
        if (root.getText().isBlank()) return;
        Path p = Paths.get(root.getText().trim());
        for (Flavor f : FlavorDetector.detect(p)) flavors.addElement(f);
    }

    private void save(String profile) {
        String r = root.getText().trim();
        if (!r.isEmpty() && !Files.isDirectory(Paths.get(r))) {
            Dialogs.error(this, "That WoW folder does not exist.");
            return;
        }
        String nick = nickname.getText().trim();
        app.config().deviceNickname = nick.isEmpty() ? "My PC" : nick;
        app.config().installRoot = r.isEmpty() ? null : r;
        app.config().dataConnections = (Integer) dataConns.getValue();
        String oldTheme = app.config().theme;
        app.config().theme = (String) theme.getSelectedItem();
        List<String> ex = new ArrayList<>();
        for (String line : exclusions.getText().split("\\R")) if (!line.isBlank()) ex.add(line.trim());
        app.config().profile(profile).exclusions = ex;
        app.saveConfig();
        if (!oldTheme.equals(app.config().theme)) Theme.apply(app.config().theme, true);
        app.refreshFlavor();
        changed = true;
        dispose();
    }

    public static boolean show(Window owner, ClientApp app) {
        SettingsDialog d = new SettingsDialog(owner, app);
        d.setVisible(true);
        return d.changed;
    }
}
