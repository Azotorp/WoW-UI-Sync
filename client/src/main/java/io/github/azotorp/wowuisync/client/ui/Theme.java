package io.github.azotorp.wowuisync.client.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import java.awt.Color;

/** FlatLaf dark and light themes, plus status colors. */
public final class Theme {
    public static final Color GREEN = new Color(0x2E9E5B);
    public static final Color BLUE = new Color(0x3B82C4);
    public static final Color ORANGE = new Color(0xD08A1E);
    public static final Color RED = new Color(0xC8423B);
    public static final Color GRAY = new Color(0x7A7F87);

    private Theme() {}

    public static void apply(String theme, boolean update) {
        if ("light".equals(theme)) FlatLightLaf.setup();
        else FlatDarkLaf.setup();
        if("high-contrast".equals(theme)) {
            javax.swing.UIManager.put("Panel.background",Color.BLACK);javax.swing.UIManager.put("Label.foreground",Color.WHITE);
            javax.swing.UIManager.put("TextField.background",Color.BLACK);javax.swing.UIManager.put("TextField.foreground",Color.WHITE);
            javax.swing.UIManager.put("Table.background",Color.BLACK);javax.swing.UIManager.put("Table.foreground",Color.WHITE);
        }
        if (update) FlatLaf.updateUI();
    }
}
