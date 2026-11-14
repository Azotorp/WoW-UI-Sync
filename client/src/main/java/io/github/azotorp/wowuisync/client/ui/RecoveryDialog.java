package io.github.azotorp.wowuisync.client.ui;
import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.sync.*;
import javax.swing.*;
import java.awt.Window;
import java.nio.file.*;
public final class RecoveryDialog {
    public static void show(Window owner,ClientApp app) {
        var f=app.flavor();if(f==null)return;Path file=ApplyJournal.journal(app.paths().root(),f.path());
        if(!Files.exists(file)){Dialogs.info(owner,"No interrupted operation for this installation.");return;}
        try{
            var op=ApplyJournal.read(file);
            Object[] choices={"Resume apply","Restore recovery snapshot","Cancel"};
            int action=JOptionPane.showOptionDialog(owner,"Interrupted operation in "+op.installationPath()+"\nRecovery snapshot: "+op.recoverySnapshotId()+"\nServer: "+op.hostPort()+"\nResume uses verified local staging. Restore may need the original server account.","Recovery",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,choices,choices[0]);
            if(action!=0&&action!=1)return;if(!WowGuard.mayChange(owner,f))return;
            if(action==1&&!app.connected()){Dialogs.info(owner,"Connect to the original account first.");return;}
            BusyDialog.run(owner,"Recovering installation",d->()->{
                SyncOps ops=new SyncOps(app);
                if(action==0)ops.resume(f);
                else ops.rollback(app.connection(),f,new TransferControl(),new TransferEngine.Sink(){public void stats(TransferStats s){d.update("Recovering",s.bytesDone.get(),s.bytesTotal);}public void connectionLost(String m){throw new IllegalStateException(m);}});
                return true;
            },r->{Dialogs.info(owner,"Recovery completed and verified.");app.fire();});
        }catch(Exception e){Dialogs.error(owner,e);}
    }
    private RecoveryDialog(){}
}
