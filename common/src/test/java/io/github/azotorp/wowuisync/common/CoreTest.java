package io.github.azotorp.wowuisync.common;

import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.codec.CompressionCategory;
import io.github.azotorp.wowuisync.common.codec.CompressionPolicy;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.manifest.ManifestIO;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import io.github.azotorp.wowuisync.common.profile.Profiles;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @Test
    void pathRules() {
        assertEquals("WTF/Account/X.lua", SyncPaths.normalize("WTF\\Account\\.\\X.lua"));
        assertThrows(IllegalArgumentException.class, () -> SyncPaths.normalize("../x"));
        assertThrows(IllegalArgumentException.class, () -> SyncPaths.normalize("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> SyncPaths.normalize("C:/x"));
        assertTrue(SyncPaths.isSyncedPath("Interface/AddOns/WeakAuras/WeakAuras.toc"));
        assertFalse(SyncPaths.isSyncedPath("Interface/AddOns/backup.zip"));
        assertTrue(SyncPaths.isSyncedPath("wtf/Account/A/SavedVariables/X.lua"));
        assertFalse(SyncPaths.isSyncedPath("Cache/x"));
        assertEquals("WeakAuras", SyncPaths.addonFolder("Interface/AddOns/WeakAuras/x/y.lua"));
        assertNotNull(SyncPaths.invalidWindowsNameReason("WTF/a?b.lua"));
        assertNotNull(SyncPaths.invalidWindowsNameReason("WTF/dir./b.lua"));
        assertNull(SyncPaths.invalidWindowsNameReason("WTF/Account/b.lua"));
        assertEquals(1, SyncPaths.caseCollisions(List.of("WTF/a.lua", "WTF/A.lua", "WTF/b.lua")).size());
    }

    @Test
    void exclusions() {
        Exclusions ex = Exclusions.defaults();
        assertTrue(ex.isExcluded("WTF/Config.wtf"));
        assertTrue(ex.isExcluded("wtf/config.wtf"));
        assertTrue(ex.isExcluded("WTF/Account/A/SavedVariables/X.lua.bak"));
        assertTrue(ex.isExcluded("WTF/GamePadConfig_1.json"));
        assertTrue(ex.isExcluded("Interface/AddOns/Foo/.git/HEAD"));
        assertTrue(ex.isExcluded("Interface/AddOns/Foo/Thumbs.db"));
        assertTrue(ex.isExcluded("Thumbs.db"));
        assertFalse(ex.isExcluded("WTF/Account/A/SavedVariables/X.lua"));
    }

    @Test
    void codecs() {
        byte[] data = "hello hello hello hello hello".repeat(100).getBytes(StandardCharsets.UTF_8);
        for (Codec c : Codec.values()) {
            assertArrayEquals(data, c.decompress(c.compress(data), data.length));
            assertArrayEquals(data, c.decompress(c.compress(data), -1));
        }
        assertArrayEquals(data, Codec.decodeTagged(Codec.preferred().encodeTagged(data)));
        assertEquals(CompressionCategory.ALREADY_COMPRESSED, CompressionPolicy.categoryOf("a/b.OGG"));
        assertEquals(CompressionCategory.TEXT, CompressionPolicy.categoryOf("a/b"));
        assertEquals(CompressionCategory.UNSURE, CompressionPolicy.categoryOf("a/b.blp"));
        assertTrue(CompressionPolicy.sampleTest(data, data.length));
    }

    @Test
    void manifests() {
        Manifest a = new Manifest(List.of(new ManifestEntry("WTF/b.lua", 2, "bb"), new ManifestEntry("WTF/A.lua", 1, "aa")));
        Manifest b = new Manifest(List.of(new ManifestEntry("WTF/a.lua", 1, "aa"), new ManifestEntry("WTF/c.lua", 3, "cc")));
        assertEquals("WTF/A.lua", a.entries().get(0).path());
        assertEquals(a.fingerprint(), ManifestIO.decode(ManifestIO.encode(a)).fingerprint());
        ManifestDiff d = ManifestDiff.between(a, b);
        assertEquals(1, d.added().size());
        assertEquals(1, d.edited().size());
        assertEquals(1, d.removed().size());
        assertEquals(b.fingerprint(), d.applyTo(a).fingerprint());
        assertEquals("forever", Profiles.profileIdFromFolder("_forever_"));
    }
}
