package io.github.azotorp.wowuisync.common;

import io.github.azotorp.wowuisync.common.addon.AddonGroup;
import io.github.azotorp.wowuisync.common.addon.AddonGrouper;
import io.github.azotorp.wowuisync.common.addon.DataMapper;
import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullPlanner;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.addon.TocInfo;
import io.github.azotorp.wowuisync.common.addon.TocParser;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.pack.PackKind;
import io.github.azotorp.wowuisync.common.pack.PackReader;
import io.github.azotorp.wowuisync.common.pack.PackWriter;
import io.github.azotorp.wowuisync.common.protocol.InviteCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlanTest {
    @Test
    void packs() throws Exception {
        for (PackKind k : PackKind.values()) {
            PackWriter w = new PackWriter(k, Codec.preferred(), 3);
            byte[] a = "aaaa".getBytes(StandardCharsets.UTF_8), b = "bbbbbbbb".getBytes(StandardCharsets.UTF_8);
            w.add("x/b.txt", Hashing.sha256Hex(b), b);
            w.add("x/a.lua", Hashing.sha256Hex(a), a);
            byte[] p = w.build();
            var pack = PackReader.read(p, 0, p.length, true);
            assertEquals(2, pack.entries().size());
            assertArrayEquals(a, pack.entries().get(0).data());
        }
    }

    @Test
    void grouping() {
        TocInfo dbm = TocParser.parse("DBM-Core", "## Title: |cffff0000Deadly|r Boss Mods\n## X-Curse-Project-ID: 3358\n".getBytes(StandardCharsets.UTF_8));
        TocInfo dbmRaids = TocParser.parse("DBM-Raids", "## Title: DBM Raids\n## Dependencies: DBM-Core\n## X-Curse-Project-ID: 3358\n".getBytes(StandardCharsets.UTF_8));
        TocInfo wa = TocParser.parse("WeakAuras", "## Title: WeakAuras\n".getBytes(StandardCharsets.UTF_8));
        TocInfo waOpt = TocParser.parse("WeakAuras_Options", "## Title: WeakAuras Options\n## Dependencies: WeakAuras\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("Deadly Boss Mods", dbm.title());
        List<AddonGroup> g = AddonGrouper.group(List.of("WeakAuras", "WeakAuras_Options", "DBM-Core", "DBM-Raids", "Details"),
                Map.of("weakauras", wa, "weakauras_options", waOpt, "dbm-core", dbm, "dbm-raids", dbmRaids));
        assertEquals(4, g.size());
        AddonGroup w = g.stream().filter(x -> x.mainFolder().equals("WeakAuras")).findFirst().orElseThrow();
        assertEquals(2, w.folders().size());
    }

    @Test
    void pullPlan() {
        Set<String> known = Set.of("weakauras", "details");
        assertEquals(DataMapper.Kind.ADDON_DATA, DataMapper.classify("WTF/Account/ACC/SavedVariables/WeakAuras.lua", known).kind());
        assertEquals(DataMapper.Kind.ADDON_DATA, DataMapper.classify("WTF/Account/ACC/Realm/Char/SavedVariables/Details.lua.bak", known).kind());
        assertEquals(DataMapper.Kind.GENERAL, DataMapper.classify("WTF/Account/ACC/SavedVariables/Blizzard_X.lua", known).kind());
        assertEquals(DataMapper.Kind.GENERAL, DataMapper.classify("WTF/Account/ACC/macros-cache.txt", known).kind());

        Manifest source = new Manifest(List.of(
                e("Interface/AddOns/WeakAuras/WeakAuras.toc", "s1"),
                e("Interface/AddOns/Details/Details.toc", "s2"),
                e("WTF/Account/A/SavedVariables/WeakAuras.lua", "s3"),
                e("WTF/Account/A/macros-cache.txt", "s4")));
        Manifest local = new Manifest(List.of(
                e("Interface/AddOns/WeakAuras/WeakAuras.toc", "l1"),
                e("Interface/AddOns/Other/Other.toc", "l2"),
                e("WTF/Account/A/SavedVariables/WeakAuras.lua", "l3"),
                e("WTF/Account/A/SavedVariables/Other.lua", "l5"),
                e("WTF/Account/A/macros-cache.txt", "l4")));
        ManifestDiff d = PullPlanner.plan(source, local, new PullSelection(List.of("WeakAuras"), List.of(), false), PullMode.REPLACE);
        assertEquals(0, d.added().size());
        assertEquals(1, d.edited().size());
        assertEquals(1, d.removed().size()); // Other addon folder; its data stays
        ManifestDiff k = PullPlanner.plan(source, local, new PullSelection(List.of("WeakAuras", "Details"), List.of("WeakAuras"), true), PullMode.KEEP);
        assertEquals(1, k.added().size());
        assertEquals(3, k.edited().size());
        assertEquals(0, k.removed().size());
        assertTrue(PullPlanner.selectsEverything(source, new PullSelection(List.of("WeakAuras", "Details"), List.of("WeakAuras", "Details"), true), PullMode.REPLACE));
    }

    @Test
    void invite() {
        InviteCode c = new InviteCode("example.org", 2026, "ab", "secret");
        assertEquals(c, InviteCode.decode(c.encode()));
    }

    private static ManifestEntry e(String p, String h) {
        return new ManifestEntry(p, 1, h);
    }
}
