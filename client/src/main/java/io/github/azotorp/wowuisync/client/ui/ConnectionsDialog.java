package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;

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
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/** Connection manager and Connect screen (DESIGN.md §17.2), like WinSCP's site manager. */
public final class ConnectionsDialog extends JDialog {
    private final ClientApp app;
    private final DefaultListModel<ConnectionRecord> model = new DefaultListModel<>();
    private final JList<ConnectionRecord> list = new JList<>(model);
    private final Consumer<ConnectionRecord> onConnect;

    public ConnectionsDialog(Window owner, ClientApp app, Consumer<ConnectionRecord> onConnect) {
        super(owner, "Connect", ModalityType.APPLICATION_MODAL);
        this.app = app;
        this.onConnect = onConnect;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, v, i, sel, foc) -> {
            JLabel lab = new JLabel("<html><b>" + Dialogs.escape(v.name()) + "</b><br><small>" + Dialogs.escape(v.username())
                    + " @ " + Dialogs.escape(v.host()) + ":" + v.port() + " · " + (v.usesKey() ? "key" : "password") + "</small></html>");
            lab.setOpaque(true);
            lab.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            lab.setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            lab.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            return lab;
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) connect();
            }
        });
        reload();

        JButton newB = new JButton("New…");
        JButton edit = new JButton("Edit…");
        JButton del = new JButton("Delete");
        JButton connect = new JButton("Connect");
        JPanel side = new JPanel(new GridLayout(0, 1, 0, 6));
        side.add(newB);
        side.add(edit);
        side.add(del);
        JPanel sideWrap = new JPanel(new BorderLayout());
        sideWrap.add(side, BorderLayout.NORTH);
        sideWrap.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        newB.addActionListener(e -> {
            ConnectionRecord r = ConnectionEditDialog.edit(this, null, app.db());
            if (r != null) store(r);
        });
        edit.addActionListener(e -> {
            ConnectionRecord sel = list.getSelectedValue();
            if (sel == null) return;
            ConnectionRecord r = ConnectionEditDialog.edit(this, sel, app.db());
            if (r != null) store(r);
        });
        del.addActionListener(e -> {
            ConnectionRecord sel = list.getSelectedValue();
            if (sel == null) return;
            if (Dialogs.confirm(this, "Delete the connection '" + sel.name() + "'? This only removes it from this PC.")) {
                try {
                    app.db().deleteConnection(sel.id());
                    reload();
                } catch (Exception ex) {
                    Dialogs.error(this, ex);
                }
            }
        });
        connect.addActionListener(e -> connect());

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        extraButtons(left);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        right.add(connect);
        right.add(close);
        bottom.add(left, BorderLayout.WEST);
        bottom.add(right, BorderLayout.EAST);
        getRootPane().setDefaultButton(connect);

        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(420, 260));
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(new JLabel("Saved connections. Choosing a connection never changes your files."), BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(sideWrap, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Hook for the Join with invite code and Forgot password buttons. */
    private void extraButtons(JPanel p) {
        JButton join = new JButton("Join with invite code…");
        join.addActionListener(e -> {
            ConnectionRecord r = JoinInviteDialog.show(this, app);
            if (r != null) {
                reload();
                list.setSelectedValue(r, true);
            }
        });
        JButton forgot = new JButton("Forgot password…");
        forgot.addActionListener(e -> ForgotPasswordDialog.show(this, app, list.getSelectedValue()));
        p.add(join);
        p.add(forgot);
    }

    private void store(ConnectionRecord r) {
        try {
            app.db().saveConnection(r);
            reload();
            for (int i = 0; i < model.size(); i++) if (model.get(i).id().equals(r.id())) list.setSelectedIndex(i);
        } catch (Exception ex) {
            Dialogs.error(this, ex);
        }
    }

    private void reload() {
        String keep = list.getSelectedValue() == null ? app.config().lastConnectionId : list.getSelectedValue().id();
        model.clear();
        try {
            for (ConnectionRecord c : app.db().connections()) model.addElement(c);
        } catch (Exception e) {
            Dialogs.error(this, e);
        }
        for (int i = 0; i < model.size(); i++) if (model.get(i).id().equals(keep)) list.setSelectedIndex(i);
        if (list.getSelectedIndex() < 0 && !model.isEmpty()) list.setSelectedIndex(0);
    }

    private void connect() {
        ConnectionRecord sel = list.getSelectedValue();
        if (sel == null) {
            Dialogs.info(this, "Create a connection first (New…), or join with an invite code.");
            return;
        }
        dispose();
        onConnect.accept(sel);
    }
}
