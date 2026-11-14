package io.github.azotorp.wowuisync.client.sync;
import io.github.azotorp.wowuisync.client.ui.ProgressPanel;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProgressTest {
 static JProgressBar bar(Container c){for(Component x:c.getComponents()){if(x instanceof JProgressBar b)return b;if(x instanceof Container child){var b=bar(child);if(b!=null)return b;}}return null;}
 @Test void unknownWorkIsIndeterminateAndMeasuredWorkHasProgress()throws Exception{
  ProgressPanel[] p=new ProgressPanel[1];SwingUtilities.invokeAndWait(()->p[0]=new ProgressPanel());
  p[0].begin("Checking",false);p[0].phase("Discovering");p[0].count("Files discovered",100,0);
  SwingUtilities.invokeAndWait(()->assertTrue(bar(p[0]).isIndeterminate()));
  p[0].count("Files checked",2,4);
  SwingUtilities.invokeAndWait(()->{assertFalse(bar(p[0]).isIndeterminate());assertEquals(500,bar(p[0]).getValue());});
  p[0].done("Complete");SwingUtilities.invokeAndWait(()->assertEquals(1000,bar(p[0]).getValue()));
 }
 @Test void cancellationClosesOutstandingWorkExactlyOnceIncludingRegistrationRace(){
  var c=new TransferControl();var calls=new AtomicInteger();c.onCancel(calls::incrementAndGet);c.cancel();c.cancel();assertEquals(1,calls.get());
  var early=new TransferControl();early.cancel();early.onCancel(calls::incrementAndGet);assertEquals(2,calls.get());
 }
}
