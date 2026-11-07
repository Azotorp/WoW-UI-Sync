package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.codec.Codec;

import javax.swing.JOptionPane;
import java.awt.Component;

/** About: version, GPLv3 notice, Blizzard disclaimer (DESIGN.md §17.3, §22). */
public final class AboutDialog {
    private AboutDialog() {}

    public static void show(Component parent) {
        String text = Version.APP_NAME + " " + Version.APP_VERSION + " (protocol " + Version.PROTOCOL_VERSION + ")\n"
                + "Java " + Runtime.version() + " · compression: " + (Codec.zstdAvailable() ? "zstd" : "deflate") + "\n\n"
                + "Copyright (C) the WoW UI Sync contributors.\n\n"
                + Version.LICENSE_NOTICE + "\n\n" + Version.DISCLAIMER;
        JOptionPane.showMessageDialog(parent, Dialogs.wrap(text), "About " + Version.APP_NAME, JOptionPane.INFORMATION_MESSAGE);
    }
}
