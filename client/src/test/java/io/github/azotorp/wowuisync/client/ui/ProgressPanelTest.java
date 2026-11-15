package io.github.azotorp.wowuisync.client.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgressPanelTest {
    static JLabel label(Container parent,String name) {
        for(Component child:parent.getComponents()) {
            if(child instanceof JLabel l && name.equals(l.getName()))return l;
            if(child instanceof Container c){JLabel l=label(c,name);if(l!=null)return l;}
        }
        return null;
    }
    static void layout(Container parent) {
        parent.doLayout();for(Component child:parent.getComponents())if(child instanceof Container c)layout(c);
    }
    @Test void scanStatisticsAndCurrentPathStayOnSeparateRowsAndReset() throws Exception {
        ProgressPanel[] panel={null};
        String path="Interface/AddOns/Example/Media/Textures/MapChart/Rays2.blp";
        SwingUtilities.invokeAndWait(()->{
            var p=panel[0]=new ProgressPanel();p.begin("Scanning",true);
            p.count("Scanning files",7651,35962);p.scanMetrics(0,7651,415L<<20,18.8,69.4,8);p.currentFile(path);
        });
        SwingUtilities.invokeAndWait(()->{
            var p=panel[0];var current=label(p,"currentFile");
            assertEquals(path,current.getText());assertEquals(path,current.getToolTipText());
            assertFalse(label(p,"dataStatistics").getText().contains("Interface/"));
            assertFalse(label(p,"rateStatistics").getText().contains("Interface/"));
            p.setSize(900,230);layout(p);
            Point data=SwingUtilities.convertPoint(label(p,"dataStatistics").getParent(),label(p,"dataStatistics").getLocation(),p);
            Point file=SwingUtilities.convertPoint(current.getParent(),current.getLocation(),p);
            assertTrue(file.y>data.y);
            String preview=System.getProperty("wowuisync.progressPreview");
            if(preview!=null) {
                var image=new java.awt.image.BufferedImage(900,230,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();p.paint(g);g.dispose();
                try {javax.imageio.ImageIO.write(image,"png",java.nio.file.Path.of(preview).toFile());}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            }
            p.done("Scan complete");
        });
        SwingUtilities.invokeAndWait(()->assertEquals(" ",label(panel[0],"currentFile").getText()));
    }    @Test void backupPhaseClearsScanSummaryAndKeepsPathSeparate() throws Exception {
        ProgressPanel[] panel={null};
        SwingUtilities.invokeAndWait(()->{var p=panel[0]=new ProgressPanel();p.begin("Pull",true);p.scanMetrics(36186,0,0,2.397,-1,8);p.note("Old scan summary");p.currentFile("old.lua");p.phase("Backing up");});
        SwingUtilities.invokeAndWait(()->{var p=panel[0];assertEquals(" ",label(p,"dataStatistics").getText());assertEquals(" ",label(p,"rateStatistics").getText());assertEquals(" ",label(p,"currentFile").getText());p.fileOperation("Backing up","Interface/AddOns/Example/file.lua",2148,36387,100,1000);});
        SwingUtilities.invokeAndWait(()->{
            var p=panel[0];assertEquals("Files: 2148 of 36387",label(p,"fileStatistics").getText());
            assertEquals("Interface/AddOns/Example/file.lua",label(p,"currentFile").getText());
            assertFalse(label(p,"dataStatistics").getText().contains("cache"));
            assertFalse(label(p,"fileStatistics").getText().contains("Interface"));
        });
    }

}
