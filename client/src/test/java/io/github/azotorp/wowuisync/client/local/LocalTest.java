package io.github.azotorp.wowuisync.client.local;

import io.github.azotorp.wowuisync.client.data.BackupRecord;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.sync.LocalScan;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalTest {
    @TempDir
    Path dir;

    private static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }

    @Test
    void scanBackupRestore() throws Exception {
        Path flavor = dir.resolve("_forever_");
        write(flavor.resolve("Interface/AddOns/WeakAuras/WeakAuras.toc"), "## Title: WeakAuras\n");
        write(flavor.resolve("Interface/AddOns/WeakAuras_Options/WeakAuras_Options.toc"), "## Dependencies: WeakAuras\n");
        write(flavor.resolve("Interface/AddOns/WeakAuras/.git/HEAD"), "ref");
        write(flavor.resolve("Interface/AddOns/loose.zip"), "zip");
        write(flavor.resolve("WTF/Config.wtf"), "cfg");
        write(flavor.resolve("WTF/Account/A/SavedVariables/WeakAuras.lua"), "x = 1");
        write(flavor.resolve("WTF/Account/A/SavedVariables/WeakAuras.lua.bak"), "x = 0");
        try (ClientDb db = ClientDb.open(dir.resolve("data/client.db"), dir.resolve("data/b"))) {
            LocalScan s = Scanner.scan(flavor, Exclusions.defaults(), db, null, () -> false);
            assertEquals(3, s.manifest().size(), s.manifest().entries().toString());
            LocalScan s2 = Scanner.scan(flavor, Exclusions.defaults(), db, null, () -> false);
            assertEquals(s.manifest().fingerprint(), s2.manifest().fingerprint());

            List<AddonManager.LocalAddon> addons = AddonManager.list(flavor);
            assertEquals(1, addons.size());
            assertEquals(2, addons.get(0).dataFiles().size());

            BackupRecord b = Backups.create(db, flavor, Backups.REASON_MANUAL, null, null);
            assertTrue(Files.isDirectory(flavor.resolve("WTF_" + b.suffix())));
            AddonManager.deleteAddon(flavor, addons.get(0));
            write(flavor.resolve("WTF/new.txt"), "n");
            assertFalse(Files.exists(flavor.resolve("Interface/AddOns/WeakAuras")));
            Thread.sleep(1100);
            Backups.restore(db, flavor, "forever", b, null);
            assertTrue(Files.exists(flavor.resolve("Interface/AddOns/WeakAuras/WeakAuras.toc")));
            assertFalse(Files.exists(flavor.resolve("WTF/new.txt")));
            assertEquals(2, Backups.list(db, flavor).size());
            assertEquals("local", Backups.list(db, flavor).get(0).account());
        }
    }
}
