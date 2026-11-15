package io.github.azotorp.wowuisync.common;
import io.github.azotorp.wowuisync.common.addon.*;
import io.github.azotorp.wowuisync.common.manifest.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddonRedesignTest {
    static TocInfo toc(String f,String text){return TocParser.parse(f,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static ManifestEntry file(String path){return new ManifestEntry(path,0,"a".repeat(64));}
    static Manifest manifest(String...paths){return new Manifest(Arrays.stream(paths).map(AddonRedesignTest::file).toList());}
    static String addon(String f){return "Interface/AddOns/"+f+"/"+f+".toc";}
    static String data(String v){return "WTF/Account/EXAMPLE/SavedVariables/"+v+".lua";}
    @Test void referenceMetadataRetainsFolderFamilyAndExternalDependencies() throws Exception {
        Map<String,TocInfo> tocs=new HashMap<>();
        try(var input=getClass().getResourceAsStream("/sanitized-addon-metadata.json")) {
            var tree=io.github.azotorp.wowuisync.common.json.Json.MAPPER.readTree(input);
            tree.fields().forEachRemaining(e->tocs.put(e.getKey().toLowerCase(Locale.ROOT),toc(e.getKey(),e.getValue().asText())));
        }
        List<ManifestEntry> entries=new ArrayList<>();
        tocs.values().forEach(t->entries.add(file(addon(t.folder()))));
        for(String root:List.of("ForeverAuras","EllesmereUI","ElvUI"))entries.add(file(addon(root)));
        var catalog=new AddonCatalog(new Manifest(entries),tocs);
        assertEquals(entries.size(),catalog.rows().size());
        var module=catalog.rows().stream().filter(x->x.folder().equals("CustomGameMenuButtons-ForeverAuras")).findFirst().orElseThrow();
        assertEquals("CustomGameMenuButtons",module.family().name());
        assertTrue(module.dependencies().contains("ForeverAuras (required)"));
        assertTrue(module.dependencies().contains("ElvUI (optional)"));
        assertEquals("",catalog.family("ForeverAuras").name());
        assertEquals("EllesmereUI",catalog.family("EllesmereUICustomTags").name());
        assertEquals("Explicit TOC Group",catalog.family("EllesmereUIBags").source());
        assertEquals(List.of("EllesmereUIBagsDB","EllesmereUIBagsInventoryDB"),tocs.get("ellesmereuibags").savedVariables());
        assertEquals(List.of("EllesmereUIChatScrollDB"),tocs.get("ellesmereuichat").perCharacterVariables());
        assertEquals("CustomGameMenuButtons-ForeverAuras",catalog.token(data("CustomGameMenuButtons_ForeverAurasDB")));
    }
    @Test void bomMalformedAliasesAndMultiTocPrecedence() {
        var t=toc("A","\ufeff## tItLe: |cFF00ccffName|r |Ticon:12|t\r\n## RequiredDeps: B, b, C\r\n## OptionalDeps: D, d\r\n## Group:   Family   Name \r\n## SavedVariables: DB, DB2\r\n## SavedVariablesPerCharacter: CharacterDB\r\n## bad\n");
        assertEquals("Name",t.title());assertEquals(List.of("B","C"),t.dependencies());assertEquals(List.of("D"),t.optionalDependencies());
        assertEquals("Family Name",t.group());
        var m=manifest(addon("A"),"Interface/AddOns/A/A_Forever.toc","Interface/AddOns/A/A_Mainline.toc");
        assertEquals(1,AddonGrouper.folders(m).size());
        assertTrue(AddonGrouper.tocEntries(m,"forever").get("a").path().endsWith("A_Forever.toc"));
        assertEquals(addon("A"),AddonGrouper.tocEntries(m,"classic_era").get("a").path());
        assertTrue(AddonGrouper.tocEntries(manifest("Interface/AddOns/A/A_Mainline.toc"),"forever").isEmpty());
    }
    @Test void ownershipScopesAmbiguityAndBlizzardOrder() {
        var tocs=Map.of("a",toc("A","## SavedVariables: AccountDB, SharedDB, Blizzard_Owned\n## SavedVariablesPerCharacter: CharacterDB"),"b",toc("B","## SavedVariables: SharedDB"));
        var m=manifest(addon("A"),addon("B"),data("AccountDB"),data("AccountDB")+".bak",data("SharedDB"),data("Blizzard_Owned"),data("ZUnknown"),data("Blizzard_Z"),data("Blizzard_A"),
                "WTF/Account/OTHER/SavedVariables/AccountDB.lua","WTF/Account/EXAMPLE/Realm/Character/SavedVariables/CharacterDB.lua",data("CharacterDB"));
        var catalog=new AddonCatalog(m,tocs);
        assertEquals("A",catalog.token(data("AccountDB")+".bak"));
        assertEquals("A",catalog.token("WTF/Account/EXAMPLE/Realm/Character/SavedVariables/CharacterDB.lua"));
        assertTrue(catalog.token(data("CharacterDB")).startsWith("@data:"));
        assertEquals("A",catalog.token(data("Blizzard_Owned")));
        var orphans=catalog.rows().stream().filter(AddonCatalog.Row::dataOnly).toList();
        assertTrue(orphans.get(0).name().startsWith("Blizzard_A"));assertTrue(orphans.get(1).name().startsWith("Blizzard_Z"));
        assertEquals("Blizzard (assumed)",orphans.get(0).family().source());
        assertTrue(orphans.stream().anyMatch(x->x.dependencies().contains("Shared ownership: A, B")));
        assertNull(catalog.token("WTF/Account/EXAMPLE/macros-cache.txt"));
    }
    @Test void selectivePullNeverDeletesUncheckedFilesDataOrLocalOnlyFolders() {
        var src=manifest(addon("A"),data("OwnedDB"),data("Blizzard_A"),data("Unknown"),"WTF/Account/EXAMPLE/macros-cache.txt");
        var local=manifest(addon("A"),addon("Local"),data("OwnedDB"),data("Details"),"Interface/AddOns/A/stale.lua");
        var tocs=Map.of("a",toc("A","## SavedVariables: OwnedDB"));
        var dataOnly=new PullSelection(List.of(),List.of("A"),false);
        var result=PullPlanner.target(src,local,dataOnly,tocs,tocs);
        assertNotNull(result.get("Interface/AddOns/A/stale.lua"));assertNotNull(result.get(addon("Local")));assertNotNull(result.get(data("Details")));
        assertNull(result.get(data("Blizzard_A")));assertNull(result.get(data("Unknown")));
        var general=PullPlanner.target(src,local,new PullSelection(List.of(),List.of(),true),tocs,tocs);
        assertNotNull(general.get("WTF/Account/EXAMPLE/macros-cache.txt"));assertNull(general.get(data("Unknown")));
        var selected=new PullSelection(List.of("A"),List.of(),false,List.of("Local"),List.of());
        result=PullPlanner.target(src,local,selected,tocs,tocs);
        assertNull(result.get(addon("Local")));assertNull(result.get("Interface/AddOns/A/stale.lua"));assertNotNull(result.get(data("OwnedDB")));
        assertThrows(IllegalArgumentException.class,()->PullPlanner.target(src,local,new PullSelection(List.of(),List.of(),false,List.of("A"),List.of()),tocs,tocs));
    }
    @Test void localTocsNeverSilentlyReassignServerOrphans() {
        var src=manifest(data("OldDB"));var local=manifest(addon("NewAddon"));var localTocs=Map.of("newaddon",toc("NewAddon","## SavedVariables: OldDB"));
        var result=PullPlanner.target(src,local,new PullSelection(List.of(),List.of("NewAddon"),false),Map.of(),localTocs);
        assertNull(result.get(data("OldDB")));
        var orphan=new PullSelection(List.of(),List.of(),false,List.of(),List.of("@data:account:OldDB"));
        assertNotNull(PullPlanner.target(src,local,orphan,Map.of(),localTocs).get(data("OldDB")));
    }
}
