package io.github.azotorp.wowuisync.common.ui;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.*;
/** Optional decorative image; missing artwork always falls back to the normal solid background. */
public final class WallpaperPanel extends JPanel {
 private BufferedImage image;private final BooleanSupplier enabled;private final IntSupplier opacity;
 public WallpaperPanel(LayoutManager layout,String resource,BooleanSupplier enabled,IntSupplier opacity){
  super(layout);this.enabled=enabled;this.opacity=opacity;
  try(var in=WallpaperPanel.class.getResourceAsStream(resource)){if(in!=null)image=javax.imageio.ImageIO.read(in);}catch(Exception ignored){}
 }
 @Override protected void paintComponent(Graphics graphics){
  super.paintComponent(graphics);if(image==null||!enabled.getAsBoolean())return;
  Graphics2D g=(Graphics2D)graphics.create();try{
   g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0,Math.min(100,opacity.getAsInt()))/100f));
   double scale=Math.max(getWidth()/(double)image.getWidth(),getHeight()/(double)image.getHeight());
   int w=(int)(image.getWidth()*scale),h=(int)(image.getHeight()*scale);g.drawImage(image,(getWidth()-w)/2,(getHeight()-h)/2,w,h,null);
  }finally{g.dispose();}
 }
}
