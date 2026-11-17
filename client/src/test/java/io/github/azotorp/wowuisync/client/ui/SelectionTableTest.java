package io.github.azotorp.wowuisync.client.ui;
import io.github.azotorp.wowuisync.common.addon.AddonCatalog;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SelectionTableTest {
    @Test void serverOnlyPurgeCandidatesExcludePresentFoldersAndSavedVariables() {
        var local=new io.github.azotorp.wowuisync.common.manifest.Manifest(List.of(
                new io.github.azotorp.wowuisync.common.manifest.ManifestEntry("Interface/AddOns/current/current.toc",1,"a".repeat(64)),
                new io.github.azotorp.wowuisync.common.manifest.ManifestEntry("WTF/Account/ACC/SavedVariables/Old.lua",1,"b".repeat(64))));
        var remote=new io.github.azotorp.wowuisync.common.manifest.Manifest(List.of(
                new io.github.azotorp.wowuisync.common.manifest.ManifestEntry("Interface/AddOns/Current/Current.toc",1,"a".repeat(64)),
                new io.github.azotorp.wowuisync.common.manifest.ManifestEntry("Interface/AddOns/Old/Old.toc",1,"b".repeat(64))));
        assertEquals(List.of("Old"),PullSelectionDialog.destinationOnlyFolders(local,remote));
        assertEquals(List.of(),PullSelectionDialog.destinationOnlyFolders(remote,local));
    }
    @Test void numericSortingAndDataOnlyControls() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var family=new AddonCatalog.Family("","");
            var a=new PullSelectionDialog.Entry(new AddonCatalog.Row("A","A","A",family,"",1000,0,false,List.of()));
            var b=new PullSelectionDialog.Entry(new AddonCatalog.Row("B","B","B",family,"",20,200,false,List.of()));
            var orphan=new PullSelectionDialog.Entry(new AddonCatalog.Row("@data:account:X","X","",family,"",0,3,true,List.of("WTF/Account/EXAMPLE/SavedVariables/X.lua")));
            var model=new PullSelectionDialog.Model(new ArrayList<>(List.of(a,b,orphan)),()->{});
            JTable table=new JTable(model);table.setAutoCreateRowSorter(true);table.getRowSorter().toggleSortOrder(6);
            assertEquals(2,table.convertRowIndexToModel(0));assertEquals(1,table.convertRowIndexToModel(1));
            assertEquals(Long.class,model.getColumnClass(6));assertEquals(0L,model.getValueAt(0,7));
            assertFalse(model.isCellEditable(2,0));assertEquals(false,model.getValueAt(2,0));assertTrue(model.isCellEditable(2,1));assertEquals(true,model.getValueAt(2,1));
            model.setValueAt(false,2,1);assertFalse(orphan.data);
        });
    }
}
