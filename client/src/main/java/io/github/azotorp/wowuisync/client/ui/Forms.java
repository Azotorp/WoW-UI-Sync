package io.github.azotorp.wowuisync.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** Two-column label/field form builder. */
public final class Forms {
    private final JPanel panel = new JPanel(new GridBagLayout());
    private int row;

    public Forms() {
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    public Forms add(String label, JComponent field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.LINE_START;
        l.insets = new Insets(4, 0, 4, 10);
        panel.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row++;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(4, 0, 4, 0);
        panel.add(field, f);
        return this;
    }

    public Forms full(JComponent c) {
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 0;
        f.gridy = row++;
        f.gridwidth = 2;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(4, 0, 4, 0);
        panel.add(c, f);
        return this;
    }

    public JPanel panel() {
        return panel;
    }
}
