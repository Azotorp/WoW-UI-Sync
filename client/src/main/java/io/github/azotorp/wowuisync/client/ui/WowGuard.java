package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.client.wow.WowProcess;

import java.awt.Component;

/** WoW-running checks before changing a flavor folder (DESIGN.md §16). */
public final class WowGuard {
    private WowGuard() {}

    /** For pull, restore, addon delete: refuses while running. Returns true to proceed. */
    public static boolean mayChange(Component parent, Flavor f) {
        WowProcess.State s = WowProcess.check(f.path());
        if (s == WowProcess.State.RUNNING) {
            Dialogs.warn(parent, "Close World of Warcraft (" + f.displayName() + ") first.");
            return false;
        }
        if (s == WowProcess.State.UNKNOWN) {
            return Dialogs.confirmWarning(parent, "Couldn't check whether WoW is running. Make sure World of Warcraft ("
                    + f.displayName() + ") is closed. Continue?");
        }
        return true;
    }

    /** For push: allowed with a warning while running. */
    public static boolean mayPush(Component parent, Flavor f) {
        WowProcess.State s = WowProcess.check(f.path());
        if (s == WowProcess.State.RUNNING) {
            return Dialogs.confirmWarning(parent, "World of Warcraft (" + f.displayName() + ") is running. WoW saves addon data "
                    + "when you log out or reload, so files may still change. Push anyway?");
        }
        if (s == WowProcess.State.UNKNOWN) {
            return Dialogs.confirmWarning(parent, "Couldn't check whether WoW is running. Push anyway?");
        }
        return true;
    }
}
