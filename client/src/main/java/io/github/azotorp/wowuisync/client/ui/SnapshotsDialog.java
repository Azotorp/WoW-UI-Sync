package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.*;
import io.github.azotorp.wowuisync.client.sync.*;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.util.Sizes;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Snapshot history and restore controls. Recovery snapshots never become the sync head. */
public final class SnapshotsDialog extends JDialog {
    private final ClientApp app;private final Flavor flavor;private final Msg.AccountInfo account;
    private final SyncOps ops;private final List<Msg.SnapshotInfo> rows=new ArrayList<>();
    private final JTextField filter=new JTextField(24);private final Model model=new Model();private final JTable table=new JTable(model);
    private final JLabel summary=new JLabel(" ");
    private final class Model extends AbstractTableModel {
        String[] cols={"Time / origin","Device","Files","Logical size","Label","State","ID"};
        public int getRowCount(){return rows.size();} public int getColumnCount(){return cols.length;}
        public String getColumnName(int i){return cols[i];}
        public Object getValueAt(int r,int c){var s=rows.get(r);return switch(c){
            case 0->java.time.Instant.ofEpochMilli(s.createdAt()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()+" · "+s.sourceType();
            case 1->s.device();case 2->s.fileCount();case 3->Sizes.human(s.logicalBytes());case 4->s.label();
            case 5->(s.current()?"Sync head ":"")+(s.pinned()?"Pinned":"Retained");default->s.id();};}
    }
    private SnapshotsDialog(Window owner,ClientApp app,Flavor f) {
        super(owner,"Snapshots · "+f.displayName(),ModalityType.APPLICATION_MODAL);this.app=app;flavor=f;account=app.targetAccount();ops=new SyncOps(app);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(true);
        var sorter=(javax.swing.table.TableRowSorter<?>)table.getRowSorter();
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            void update(){sorter.setRowFilter(RowFilter.regexFilter("(?i)"+java.util.regex.Pattern.quote(filter.getText())));}
            public void insertUpdate(javax.swing.event.DocumentEvent e){update();}public void removeUpdate(javax.swing.event.DocumentEvent e){update();}public void changedUpdate(javax.swing.event.DocumentEvent e){update();}
        });
        JPanel top=new JPanel(new BorderLayout(8,8));
        top.add(new JLabel("Account "+account.username()+" · "+f.path()+" · retain all by default"),BorderLayout.NORTH);
        JPanel search=new JPanel(new FlowLayout(FlowLayout.LEFT));search.add(new JLabel("Filter device, source, label, date:"));search.add(filter);top.add(search,BorderLayout.SOUTH);
        JPanel buttons=new JPanel(new GridLayout(0,5,6,6));
        add(buttons,"Refresh",this::reload);add(buttons,"Snapshot this PC",this::manual);add(buttons,"Compare",this::compare);
        add(buttons,"Restore all…",()->restore(false));add(buttons,"Restore files…",()->restore(true));add(buttons,"Verify",this::verify);
        add(buttons,"Label",this::label);add(buttons,"Pin / unpin",this::pin);add(buttons,"Export manifest",this::export);add(buttons,"Delete…",this::delete);add(buttons,"Retention…",()->{
            String input=Dialogs.askText(this,"Keep latest N snapshots (0 = retain all). Pins, the current head and snapshots younger than 48h remain protected. Enabling this can delete older history:","0");
            if(input==null)return;
            try {int keep=Integer.parseInt(input);if(keep<0)throw new NumberFormatException();
                if(keep>0&&!Dialogs.confirmWarning(this,"Enable retention and prune older unpinned snapshots after the protection window?"))return;
                BusyDialog.run(this,"Saving retention",d->()->app.connection().call(new Msg.SnapshotRetention(account.id(),flavor.profileId(),keep),Msg.Ok.class),r->{Dialogs.info(this,r.message());reload();});
            }catch(NumberFormatException e){Dialogs.error(this,"Enter a non-negative whole number.");}
        });
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(summary,BorderLayout.NORTH);bottom.add(buttons,BorderLayout.CENTER);
        JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        root.add(top,BorderLayout.NORTH);root.add(new JScrollPane(table),BorderLayout.CENTER);root.add(bottom,BorderLayout.SOUTH);
        setContentPane(root);setSize(1150,520);setLocationRelativeTo(owner);SwingUtilities.invokeLater(this::reload);
    }
    private void add(JPanel p,String text,Runnable r){JButton b=new JButton(text);b.addActionListener(e->r.run());p.add(b);}
    private Msg.SnapshotInfo selected(){int r=table.getSelectedRow();return r<0?null:rows.get(table.convertRowIndexToModel(r));}
    private TransferEngine.Sink sink(BusyDialog d){return new TransferEngine.Sink(){
        public void stats(TransferStats s){d.update("Transferring verified chunks",s.bytesDone.get(),s.bytesTotal);}
        public void connectionLost(String message){throw new IllegalStateException(message+" Reconnect and retry.");}
    };}
    private void reload(){BusyDialog.run(this,"Reading snapshots",d->()->app.connection().call(new Msg.SnapshotList(account.id(),flavor.profileId()),Msg.SnapshotListResp.class),r->{
        rows.clear();rows.addAll(r.snapshots());model.fireTableDataChanged();summary.setText(rows.size()+" retained snapshots. Times use "+java.time.ZoneId.systemDefault()+"; exports use UTC.");
    });}
    private void manual(){BusyDialog.run(this,"Creating server snapshot",d->()->{
        var scan=ops.scan(flavor,null,()->false);return ops.recoverySnapshot(app.connection(),flavor,scan,new TransferControl(),sink(d),"manual");
    },r->{Dialogs.info(this,"Snapshot saved in your own account. The shared sync head is unchanged.");reload();});}
    private void compare(){var s=selected();if(s==null)return;
        BusyDialog.run(this,"Comparing snapshot",d->()->{
            var m=app.connection().call(new Msg.SnapshotGet(account.id(),flavor.profileId(),s.id()),Msg.ManifestResp.class);
            return ManifestDiff.between(ops.scan(flavor,null,()->false).manifest(),new Manifest(m.entries()));
        },diff->ReviewDialog.info(this,"Snapshot differences",s.id(),new String[]{"Added","Changed","Removed"},paths(diff.added()),paths(diff.edited()),paths(diff.removed())));
    }
    private void restore(boolean selectedFiles){var snapshot=selected();if(snapshot==null || !WowGuard.mayChange(this,flavor))return;
        BusyDialog.run(this,"Preparing restore",d->()->app.connection().call(new Msg.SnapshotGet(account.id(),flavor.profileId(),snapshot.id()),Msg.ManifestResp.class),remote->{
            Set<String> paths=null;
            if(selectedFiles){
                JList<String> choose=new JList<>(remote.entries().stream().map(ManifestEntry::path).toArray(String[]::new));choose.setVisibleRowCount(18);
                if(JOptionPane.showConfirmDialog(this,new JScrollPane(choose),"Select files (Ctrl/Shift to select several)",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                paths=new HashSet<>(choose.getSelectedValuesList());if(paths.isEmpty())return;
            }
            Set<String> selection=paths;
            BusyDialog.run(this,"Comparing local files",d->()->ops.scan(flavor,null,()->false),scan->{
                Map<String,ManifestEntry> target=new LinkedHashMap<>(scan.manifest().byKey());
                if(selection==null)target.clear();
                for(var e:remote.entries())if((selection==null||selection.contains(e.path()))&&!app.config().exclusions(flavor.profileId()).isExcluded(e.path()))target.put(io.github.azotorp.wowuisync.common.path.SyncPaths.key(e.path()),e);
                Manifest destination=new Manifest(target.values());var diff=ManifestDiff.between(scan.manifest(),destination);
                if(!ReviewDialog.show(this,"Review restore","Restore "+snapshot.id()+" into "+flavor.path()+". A server recovery snapshot is required first.",paths(diff.added()),paths(diff.edited()),paths(diff.removed()),"Later snapshot history is preserved.",null))return;
                if(!WowGuard.mayChange(this,flavor))return;
                var plan=new Msg.PullPlan(diff.added(),diff.edited(),paths(diff.removed()),diff.transferBytes(),destination.fingerprint(),selection==null);
                BusyDialog.run(this,"Restoring snapshot",d->()->ops.apply(app.connection(),account,flavor,scan,plan,new TransferControl(),sink(d),"before_restore"),r->{
                    Dialogs.info(this,"Restore verified. The previous local state is saved as a recovery snapshot in your account.");reload();app.fire();
                });
            });
        });
    }
    private static List<String> paths(List<ManifestEntry> e){return e.stream().map(ManifestEntry::path).toList();}
    private void verify(){var s=selected();if(s==null)return;BusyDialog.run(this,"Verifying all chunks",d->()->app.connection().call(new Msg.SnapshotVerify(account.id(),flavor.profileId(),s.id()),Msg.Ok.class),r->Dialogs.info(this,r.message()));}
    private void update(Msg.SnapshotInfo s,String label,Boolean pin,boolean delete){BusyDialog.run(this,"Updating snapshot",d->()->app.connection().call(new Msg.SnapshotUpdate(account.id(),flavor.profileId(),s.id(),label,pin,delete),Msg.Ok.class),r->reload());}
    private void label(){var s=selected();if(s==null)return;String label=Dialogs.askText(this,"Snapshot label:",s.label());if(label!=null)update(s,label,null,false);}
    private void pin(){var s=selected();if(s!=null)update(s,null,!s.pinned(),false);}
    private void delete(){var s=selected();if(s!=null && Dialogs.confirmWarning(this,"Delete snapshot "+s.id()+"? Current and pinned snapshots cannot be deleted. Unreferenced chunks remain for a 48-hour GC grace period."))update(s,null,null,true);}
    private void export(){var s=selected();if(s==null)return;JFileChooser fc=new JFileChooser();fc.setSelectedFile(new java.io.File("snapshot-"+s.id()+".json"));
        if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path path=fc.getSelectedFile().toPath();
        if(Files.exists(path)&&!Dialogs.confirmWarning(this,"Replace the selected export file?"))return;
        BusyDialog.run(this,"Exporting manifest",d->()->{var m=app.connection().call(new Msg.SnapshotGet(account.id(),flavor.profileId(),s.id()),Msg.ManifestResp.class);Files.write(path,Json.PRETTY.writeValueAsBytes(m));return true;},r->Dialogs.info(this,"Manifest exported. File contents remain on the server."));
    }
    public static void show(Window owner,ClientApp app,Flavor f){new SnapshotsDialog(owner,app,f).setVisible(true);}
}
