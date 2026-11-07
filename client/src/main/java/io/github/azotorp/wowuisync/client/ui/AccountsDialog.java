package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.util.Sizes;
import io.github.azotorp.wowuisync.common.util.Usernames;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Accounts panel with Invites tab (DESIGN.md §17.5), for root and superusers. */
public final class AccountsDialog extends JDialog {
    private final ClientApp app;
    private final ServerConnection conn;
    private final Role myRole;
    private final List<Msg.AccountInfo> accounts = new ArrayList<>();
    private final List<Msg.InviteInfo> invites = new ArrayList<>();
    private final AccountModel accountModel = new AccountModel();
    private final InviteModel inviteModel = new InviteModel();
    private final JTable accountTable = new JTable(accountModel);
    private final JTable inviteTable = new JTable(inviteModel);

    private final class AccountModel extends AbstractTableModel {
        private final String[] cols = {"Username", "Role", "Owner", "Email", "Login", "Quota", "Usage", "Last push"};

        @Override public int getRowCount() { return accounts.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Msg.AccountInfo a = accounts.get(r);
            return switch (c) {
                case 0 -> a.username();
                case 1 -> a.role() + (a.maxUsers() != null ? " (max " + a.maxUsers() + " users)" : "");
                case 2 -> a.ownerName() == null ? "" : a.ownerName();
                case 3 -> a.email() == null ? "" : a.email();
                case 4 -> "key".equals(a.authMethod()) ? "key (" + a.keyType() + ")" : "password";
                case 5 -> (a.quotaMb() == null ? "unlimited" : a.quotaMb() + " MB") + (a.groupQuotaMb() != null ? " · group " + a.groupQuotaMb() + " MB" : "");
                case 6 -> Sizes.human(a.usedBytes());
                default -> a.lastPushAt() == null ? "never" : StatusView.when(a.lastPushAt()) + " (" + a.lastPushDevice() + ")";
            };
        }
    }

    private final class InviteModel extends AbstractTableModel {
        private final String[] cols = {"Role", "Created by", "Uses left", "Expires", "Created"};

        @Override public int getRowCount() { return invites.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Msg.InviteInfo i = invites.get(r);
            return switch (c) {
                case 0 -> i.role();
                case 1 -> i.ownerName();
                case 2 -> i.usesLeft() == null ? "unlimited" : i.usesLeft().toString();
                case 3 -> i.expiresAt() == null ? "never" : StatusView.when(i.expiresAt());
                default -> StatusView.when(i.createdAt());
            };
        }
    }

    private AccountsDialog(Window owner, ClientApp app) {
        super(owner, "Accounts", ModalityType.APPLICATION_MODAL);
        this.app = app;
        this.conn = app.connection();
        this.myRole = app.role();
        accountTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        inviteTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton newAcc = new JButton("New account…");
        JButton cred = new JButton("Set password/key…");
        JButton email = new JButton("Edit email…");
        JButton quota = new JButton("Quota and caps…");
        JButton del = new JButton("Delete…");
        newAcc.addActionListener(e -> newAccount());
        cred.addActionListener(e -> selectedAccount(this::setCredential));
        email.addActionListener(e -> selectedAccount(this::editEmail));
        quota.addActionListener(e -> selectedAccount(this::editQuota));
        del.addActionListener(e -> selectedAccount(this::delete));
        JPanel ab = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : List.of(newAcc, cred, email, quota, del)) ab.add(b);
        JScrollPane asp = new JScrollPane(accountTable);
        asp.setPreferredSize(new Dimension(900, 340));
        JPanel accTab = new JPanel(new BorderLayout(0, 6));
        accTab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        accTab.add(asp, BorderLayout.CENTER);
        accTab.add(ab, BorderLayout.SOUTH);

        JButton newInv = new JButton("Create invite…");
        JButton revoke = new JButton("Revoke");
        newInv.addActionListener(e -> createInvite());
        revoke.addActionListener(e -> {
            int r = inviteTable.getSelectedRow();
            if (r < 0) return;
            Msg.InviteInfo i = invites.get(r);
            if (!Dialogs.confirm(this, "Revoke this " + i.role() + " invite? The code stops working.")) return;
            call(() -> conn.call(new Msg.InviteRevoke(i.id()), Msg.Ok.class), ok -> reload());
        });
        JPanel ib = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ib.add(newInv);
        ib.add(revoke);
        JPanel invTab = new JPanel(new BorderLayout(0, 6));
        invTab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        invTab.add(new JScrollPane(inviteTable), BorderLayout.CENTER);
        invTab.add(ib, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Accounts", accTab);
        tabs.addTab("Invites", invTab);
        JButton close = new JButton("Close");
        JButton refresh = new JButton("Refresh");
        close.addActionListener(e -> dispose());
        refresh.addActionListener(e -> reload());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(refresh);
        south.add(close);
        JPanel root = new JPanel(new BorderLayout());
        root.add(new JLabel("  " + (myRole == Role.ROOT ? "Root: you manage every account."
                : "Superuser: you manage the users you created or invited.")), BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private <T> void call(Callable<T> c, Consumer<T> ok) {
        BusyDialog.run(this, "Working", d -> c, ok);
    }

    private void reload() {
        call(() -> new Object[] {conn.call(new Msg.AccountList(), Msg.AccountListResp.class), conn.call(new Msg.InviteList(), Msg.InviteListResp.class)},
                r -> {
                    Msg.AccountListResp al = (Msg.AccountListResp) r[0];
                    Msg.InviteListResp il = (Msg.InviteListResp) r[1];
                    accounts.clear();
                    accounts.addAll(al.accounts());
                    invites.clear();
                    invites.addAll(il.invites());
                    accountModel.fireTableDataChanged();
                    inviteModel.fireTableDataChanged();
                    app.updateAccessible(al.accounts());
                });
    }

    private void selectedAccount(Consumer<Msg.AccountInfo> action) {
        int r = accountTable.getSelectedRow();
        if (r < 0) {
            Dialogs.info(this, "Select an account first.");
            return;
        }
        action.accept(accounts.get(r));
    }

    private static JTextField mbField(Long v) {
        return new JTextField(v == null ? "" : v.toString(), 10);
    }

    /** Blank = unlimited (-1 on the wire). */
    private static Long parseMb(String s) {
        String t = s.trim();
        if (t.isEmpty()) return -1L;
        long v = Long.parseLong(t);
        if (v < 0) throw new NumberFormatException();
        return v;
    }

    private void newAccount() {
        JTextField user = new JTextField(20);
        JComboBox<String> role = new JComboBox<>(myRole == Role.ROOT ? new String[] {"user", "superuser"} : new String[] {"user"});
        CredentialInput cred = new CredentialInput(true);
        JTextField email = new JTextField(24);
        JTextField quota = mbField(null);
        JTextField maxUsers = new JTextField(8);
        JTextField groupQuota = mbField(null);
        Forms f = new Forms().add("Username:", user).add("Role:", role);
        cred.addTo(f);
        f.add("Email (optional):", email).add("Quota MB (blank = unlimited):", quota);
        if (myRole == Role.ROOT) {
            f.add("Max users (superuser, blank = unlimited):", maxUsers);
            f.add("Group quota MB (superuser, blank = unlimited):", groupQuota);
        }
        while (true) {
            if (JOptionPane.showConfirmDialog(this, f.panel(), "New account", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                String um = Usernames.validationMessage(user.getText().trim());
                if (um != null) throw new IllegalArgumentException(um);
                CredentialInput.Value v = cred.value();
                Long q = parseMb(quota.getText());
                boolean su = "superuser".equals(role.getSelectedItem());
                Integer mu = su && !maxUsers.getText().isBlank() ? Integer.valueOf(maxUsers.getText().trim()) : null;
                Long gq = su ? parseMb(groupQuota.getText()) : null;
                Msg.AccountCreate m = new Msg.AccountCreate(user.getText().trim(), (String) role.getSelectedItem(), email.getText().trim(),
                        v.password(), v.publicKey(), q, mu, gq);
                call(() -> conn.call(m, Msg.AccountInfo.class), a -> {
                    Dialogs.info(this, "Account '" + a.username() + "' created.");
                    reload();
                });
                return;
            } catch (NumberFormatException e) {
                Dialogs.error(this, "Enter whole numbers for quotas and max users, or leave them blank.");
            } catch (IllegalArgumentException e) {
                Dialogs.error(this, e.getMessage());
            }
        }
    }

    private boolean canManage(Msg.AccountInfo a) {
        if (myRole == Role.ROOT) return true;
        return "user".equals(a.role()) && app.login() != null && app.login().accountId().equals(a.ownerId());
    }

    private void setCredential(Msg.AccountInfo a) {
        boolean self = app.login() != null && app.login().accountId().equals(a.id());
        if (!self && !canManage(a)) {
            Dialogs.error(this, "You cannot change that account.");
            return;
        }
        if (self) {
            MyAccountDialog.changeCredential(this, app, a);
            reload();
            return;
        }
        CredentialInput cred = new CredentialInput("key".equals(a.authMethod()));
        Forms f = new Forms();
        f.full(new JLabel("New credential for " + a.username() + ". Their active sessions end."));
        cred.addTo(f);
        while (true) {
            if (JOptionPane.showConfirmDialog(this, f.panel(), "Set password or key", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                CredentialInput.Value v = cred.value();
                call(() -> conn.call(new Msg.AccountSetCredential(a.id(), v.password(), v.publicKey()), Msg.AccountInfo.class), x -> reload());
                return;
            } catch (IllegalArgumentException e) {
                Dialogs.error(this, e.getMessage());
            }
        }
    }

    private void editEmail(Msg.AccountInfo a) {
        String e = Dialogs.askText(this, "Email for " + a.username() + " (blank to remove):", a.email() == null ? "" : a.email());
        if (e == null) return;
        call(() -> conn.call(new Msg.AccountUpdate(a.id(), e.trim(), null, null, null), Msg.AccountInfo.class), x -> reload());
    }

    private void editQuota(Msg.AccountInfo a) {
        if (!canManage(a)) {
            Dialogs.error(this, "You cannot change that account's quota.");
            return;
        }
        JTextField quota = mbField(a.quotaMb());
        JTextField maxUsers = new JTextField(a.maxUsers() == null ? "" : a.maxUsers().toString(), 8);
        JTextField group = mbField(a.groupQuotaMb());
        Forms f = new Forms().add("Quota MB (blank = unlimited):", quota);
        boolean caps = myRole == Role.ROOT && "superuser".equals(a.role());
        if (caps) {
            f.add("Max users (blank = unlimited):", maxUsers);
            f.add("Group quota MB (blank = unlimited):", group);
        }
        f.full(new JLabel("Currently using " + Sizes.human(a.usedBytes()) + "."));
        while (true) {
            if (JOptionPane.showConfirmDialog(this, f.panel(), "Quota and caps - " + a.username(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                Long q = parseMb(quota.getText());
                Integer mu = caps ? (maxUsers.getText().isBlank() ? -1 : Integer.parseInt(maxUsers.getText().trim())) : null;
                Long g = caps ? parseMb(group.getText()) : null;
                call(() -> conn.call(new Msg.AccountUpdate(a.id(), null, q, mu, g), Msg.AccountInfo.class), x -> reload());
                return;
            } catch (NumberFormatException e) {
                Dialogs.error(this, "Enter whole numbers, or leave the field blank for unlimited.");
            }
        }
    }

    private void delete(Msg.AccountInfo a) {
        if ("root".equals(a.role())) {
            Dialogs.error(this, "The root account cannot be deleted.");
            return;
        }
        if (!canManage(a)) {
            Dialogs.error(this, "You cannot delete that account.");
            return;
        }
        String extra = "superuser".equals(a.role()) ? " Its users move to root." : "";
        if (!Dialogs.confirmTyped(this, "Delete the account " + a.username() + " and all its data on the server? This cannot be undone." + extra,
                a.username())) return;
        call(() -> conn.call(new Msg.AccountDelete(a.id(), a.username()), Msg.Ok.class), ok -> reload());
    }

    private void createInvite() {
        JComboBox<String> role = new JComboBox<>(myRole == Role.ROOT ? new String[] {"user", "superuser"} : new String[] {"user"});
        JSpinner uses = new JSpinner(new SpinnerNumberModel(1, 1, 10_000, 1));
        javax.swing.JCheckBox unlimited = new javax.swing.JCheckBox("Unlimited uses");
        JSpinner days = new JSpinner(new SpinnerNumberModel(7, 0, 3650, 1));
        unlimited.addActionListener(e -> uses.setEnabled(!unlimited.isSelected()));
        Forms f = new Forms().add("Role:", role).add("Uses:", uses).add("", unlimited)
                .add("Expires after days (0 = never):", days);
        if (JOptionPane.showConfirmDialog(this, f.panel(), "Create invite", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Integer u = unlimited.isSelected() ? null : (Integer) uses.getValue();
        int d = (Integer) days.getValue();
        Long exp = d == 0 ? null : LocalDate.now().plusDays(d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        call(() -> conn.call(new Msg.InviteCreate((String) role.getSelectedItem(), u, exp), Msg.InviteCreated.class), created -> {
            reload();
            showCode(created.code());
        });
    }

    private void showCode(String code) {
        JTextArea a = new JTextArea(code, 4, 50);
        a.setLineWrap(true);
        a.setEditable(false);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null));
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel("Send this code to the person. They use 'Join with invite code' on the Connect screen."), BorderLayout.NORTH);
        p.add(new JScrollPane(a), BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        b.add(copy);
        p.add(b, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, p, "Invite code", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void show(Window owner, ClientApp app) {
        AccountsDialog d = new AccountsDialog(owner, app);
        javax.swing.SwingUtilities.invokeLater(d::reload);
        d.setVisible(true);
    }
}
