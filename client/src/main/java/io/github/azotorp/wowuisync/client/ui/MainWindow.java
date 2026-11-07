package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.protocol.Msg;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;

/** Main window (DESIGN.md §17.3). */
public final class MainWindow extends JFrame {
    private final ClientApp app;
    private final JLabel connectionLabel = new JLabel("Not connected");
    private final JButton connectButton = new JButton("Connect…");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final DefaultComboBoxModel<AccountItem> accountModel = new DefaultComboBoxModel<>();
    private final JComboBox<AccountItem> accountBox = new JComboBox<>(accountModel);
    private final DefaultComboBoxModel<Flavor> flavorModel = new DefaultComboBoxModel<>();
    private final JComboBox<Flavor> flavorBox = new JComboBox<>(flavorModel);
    private final JLabel badge = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel statusLine = new JLabel(" ");
    private final JLabel appliedLine = new JLabel(" ");
    private final JButton details = new JButton("Show details");
    private final JButton refresh = new JButton("Refresh");
    final ProgressPanel progress = new ProgressPanel();
    final JButton push = new JButton("Push");
    final JButton pull = new JButton("Pull…");
    final JButton backups = new JButton("Backups");
    final JButton addons = new JButton("Addons");
    final JButton accounts = new JButton("Accounts");
    final JButton myAccount = new JButton("My account");
    final JButton settings = new JButton("Settings");
    final JButton about = new JButton("About");
    private boolean updating;
    private final SyncController sync;

    /** Account combo entry. */
    record AccountItem(Msg.AccountInfo info) {
        @Override
        public String toString() {
            return info.username() + " (" + info.role() + ")";
        }
    }

    public MainWindow(ClientApp app) {
        super(Version.APP_NAME);
        this.app = app;
        this.sync = new SyncController(this, app);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (app.busy() && !Dialogs.confirmWarning(MainWindow.this, "A transfer is running. Quit anyway? It can be resumed later.")) return;
                if (app.connection() != null) app.connection().close();
                dispose();
                System.exit(0);
            }
        });

        // --- top: connection, account, profile
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JPanel connRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        connRow.add(connectionLabel);
        connRow.add(connectButton);
        connRow.add(disconnectButton);
        addRow(top, 0, "Connection:", connRow);
        addRow(top, 1, "Account:", accountBox);
        addRow(top, 2, "Profile:", flavorBox);
        connectButton.addActionListener(e -> openConnections());
        disconnectButton.addActionListener(e -> {
            if (app.busy()) return;
            app.disconnect();
        });
        accountBox.addActionListener(e -> {
            if (updating) return;
            AccountItem it = (AccountItem) accountBox.getSelectedItem();
            app.setTargetAccount(it == null ? null : it.info());
            refreshStatus();
        });
        flavorBox.addActionListener(e -> {
            if (updating) return;
            Flavor f = (Flavor) flavorBox.getSelectedItem();
            if (f != null && !f.enabled()) {
                Dialogs.info(this, f.valid() ? f.displayName() + " is coming later. This version syncs Forever only."
                        : f.folderName() + " does not look like a WoW install (no game files found).");
                updateFromApp();
                return;
            }
            app.setFlavor(f);
            refreshStatus();
        });

        // --- center: status and progress
        badge.setOpaque(true);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, badge.getFont().getSize2D() + 3f));
        badge.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        badge.setForeground(java.awt.Color.WHITE);
        JPanel statusBox = new JPanel(new BorderLayout(0, 6));
        statusBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Status"),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        statusBox.add(badge, BorderLayout.NORTH);
        JPanel lines = new JPanel(new GridLayout(0, 1, 0, 2));
        lines.add(statusLine);
        lines.add(appliedLine);
        statusBox.add(lines, BorderLayout.CENTER);
        JPanel sb = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sb.add(refresh);
        sb.add(details);
        statusBox.add(sb, BorderLayout.SOUTH);
        refresh.addActionListener(e -> refreshStatus());
        details.addActionListener(e -> showDetails());
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        center.add(statusBox, BorderLayout.NORTH);
        center.add(progress, BorderLayout.CENTER);

        // --- bottom: actions
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 8));
        for (JButton b : List.of(push, pull, backups, addons, accounts, myAccount, settings, about)) buttons.add(b);
        push.addActionListener(e -> onPush());
        pull.addActionListener(e -> onPull());
        backups.addActionListener(e -> onBackups());
        addons.addActionListener(e -> onAddons());
        accounts.addActionListener(e -> onAccounts());
        myAccount.addActionListener(e -> onMyAccount());
        settings.addActionListener(e -> {
            if (SettingsDialog.show(this, app)) {
                updateFromApp();
                refreshStatus();
            }
        });
        about.addActionListener(e -> AboutDialog.show(this));

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        setMinimumSize(new Dimension(760, 520));
        pack();
        setLocationRelativeTo(null);

        app.addListener(this::updateFromApp);
        app.refreshFlavor();
        updateFromApp();
    }

    private static void addRow(JPanel p, int row, String label, java.awt.Component c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.LINE_START;
        l.insets = new Insets(3, 0, 3, 10);
        p.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new Insets(3, 0, 3, 0);
        p.add(c, f);
    }

    /** Called after startup: opens Connect on first launch, or offers the last connection. */
    public void start() {
        if (app.installRoot() == null) {
            Dialogs.info(this, "Welcome to WoW UI Sync. First, choose your World of Warcraft folder in Settings.");
            SettingsDialog.show(this, app);
            updateFromApp();
        }
        openConnections();
    }

    void openConnections() {
        if (app.busy()) return;
        new ConnectionsDialog(this, app, this::connect).setVisible(true);
    }

    void connect(ConnectionRecord rec) {
        app.setBusy(true);
        connectionLabel.setText("Connecting to " + rec.host() + "…");
        Bg.run(() -> {
            ServerConnection c = ServerConnection.open(rec.host(), rec.port(), app.db(), null, new UiCallbacks(this),
                    app.config().deviceId, app.config().deviceNickname);
            try {
                c.login(rec, new UiCallbacks(this));
            } catch (Exception e) {
                c.close();
                throw e;
            }
            return c;
        }, c -> {
            app.setBusy(false);
            app.setConnection(c, rec);
            refreshStatus();
        }, e -> {
            app.setBusy(false);
            updateFromApp();
            if (!(e instanceof CancelledException)) Dialogs.error(this, e);
        });
    }

    /** Syncs widgets with {@link ClientApp} state. */
    void updateFromApp() {
        updating = true;
        try {
            boolean conn = app.connected();
            Msg.LoginOk login = app.login();
            if (conn) {
                connectionLabel.setText(app.connectionRecord().name() + " - logged in as " + login.username() + " (" + login.role() + ")");
            } else if (!app.busy()) {
                connectionLabel.setText("Not connected");
            }
            disconnectButton.setVisible(conn);
            accountModel.removeAllElements();
            if (conn) {
                for (Msg.AccountInfo a : login.accessibleAccounts()) {
                    AccountItem it = new AccountItem(a);
                    accountModel.addElement(it);
                    if (app.targetAccount() != null && a.id().equals(app.targetAccount().id())) accountModel.setSelectedItem(it);
                }
            }
            accountBox.setEnabled(conn && accountModel.getSize() > 1);
            Flavor sel = app.flavor();
            flavorModel.removeAllElements();
            for (Flavor f : app.flavors()) {
                flavorModel.addElement(f);
                if (sel != null && f.path().equals(sel.path())) flavorModel.setSelectedItem(f);
            }
            boolean idle = !app.busy();
            boolean ready = conn && app.flavor() != null && app.targetAccount() != null;
            push.setEnabled(idle && ready);
            pull.setEnabled(idle && ready);
            backups.setEnabled(idle && app.flavor() != null);
            addons.setEnabled(idle && app.flavor() != null);
            Role r = app.role();
            accounts.setVisible(r == Role.ROOT || r == Role.SUPERUSER);
            accounts.setEnabled(idle && conn);
            myAccount.setEnabled(idle && conn);
            connectButton.setEnabled(idle);
            disconnectButton.setEnabled(idle);
            accountBox.setEnabled(idle && accountBox.isEnabled());
            flavorBox.setEnabled(idle);
            refresh.setEnabled(idle);
            if (!conn) showStatus(StatusView.notConnected(app));
            else if (app.flavor() == null) showStatus(StatusView.message("Choose your WoW folder in Settings", Theme.GRAY, ""));
            updateApplied();
        } finally {
            updating = false;
        }
    }

    private void updateApplied() {
        Flavor f = app.flavor();
        if (f == null) {
            appliedLine.setText(" ");
            return;
        }
        try {
            var a = app.db().applied(f.path().toString());
            appliedLine.setText(a == null ? "Applied here: unknown (no pull or restore made by this app yet)"
                    : "Applied here: " + a.accountName() + " (since " + StatusView.when(a.appliedAt()) + ")");
        } catch (Exception e) {
            appliedLine.setText(" ");
        }
    }

    void showStatus(StatusView v) {
        badge.setText(v.badge());
        badge.setBackground(v.color());
        statusLine.setText(v.line() == null || v.line().isEmpty() ? " " : v.line());
        details.setVisible(v.detailsAvailable());
    }

    // ------------------------------------------------------------------ actions (filled in by later phases)

    void refreshStatus() {
        sync.refreshStatus();
    }

    void showDetails() {
        sync.showDetails();
    }

    void onPush() {
        sync.push();
    }

    void onPull() {
        sync.pull();
    }

    void onBackups() {
        if (app.flavor() == null) return;
        if (BackupsDialog.show(this, app, app.flavor())) {
            updateFromApp();
            refreshStatus();
        }
    }

    void onAddons() {
        if (app.flavor() == null) return;
        if (AddonsDialog.show(this, app, app.flavor())) refreshStatus();
    }

    void onAccounts() {
        Dialogs.info(this, "Account management is not available yet.");
    }

    void onMyAccount() {
        Dialogs.info(this, "My account is not available yet.");
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
