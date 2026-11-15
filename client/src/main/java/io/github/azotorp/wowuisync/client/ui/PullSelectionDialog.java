package io.github.azotorp.wowuisync.client.ui;
import io.github.azotorp.wowuisync.client.sync.*;
import io.github.azotorp.wowuisync.common.addon.*;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.util.Sizes;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** One folder per row for both directions; data-only rows and local-only removals are separate tables. */
public final class PullSelectionDialog extends JDialog {
    public record Choice(PullSelection selection, PullMode mode, boolean backupInterface, boolean backupWtf) {}
    static final class Entry {
        final AddonCatalog.Row row; boolean addon, data=true;
        Entry(AddonCatalog.Row row) { this.row=row; addon=!row.dataOnly(); }
    }
    private final List<Entry> addons=new ArrayList<>(), orphans=new ArrayList<>();
    private final List<String> localOnly=new ArrayList<>();
    private final Set<String> removals=new HashSet<>();
    private final JCheckBox general=new JCheckBox("General WTF settings (excludes all SavedVariables)",false);
    private final JCheckBox bi=new JCheckBox("Back up Interface",true), bw=new JCheckBox("Back up WTF",true);
    private final AddonCatalog catalog;
    private final Map<String,TocInfo> sourceTocs, localTocs;
    private final Manifest source, local;
    private final JLabel summary=new JLabel(" ");
    private Choice choice;
    private final boolean push;
    static final class Model extends AbstractTableModel {
        final List<Entry> entries;
        final Runnable changed;
        final String[] cols={"Addon","Include Data","Name","Folder","Family","Dependencies","Size","Data Size"};
        Model(List<Entry> entries,Runnable changed) { this.entries=entries;this.changed=changed; }
        public int getRowCount(){return entries.size();} public int getColumnCount(){return cols.length;}
        public String getColumnName(int c){return cols[c];}
        public Class<?> getColumnClass(int c){return c<2?Boolean.class:c>=6?Long.class:String.class;}
        public boolean isCellEditable(int row,int c){return c==1||c==0&&!entries.get(row).row.dataOnly();}
        public Object getValueAt(int row,int c){Entry e=entries.get(row);var x=e.row;return switch(c){
            case 0->e.addon;case 1->e.data;case 2->x.name();case 3->x.folder();case 4->x.family().name();
            case 5->x.dependencies();case 6->x.size();default->x.dataSize();};}
        public void setValueAt(Object v,int row,int c){Entry e=entries.get(row);if(c==0)e.addon=(Boolean)v;else e.data=(Boolean)v;fireTableRowsUpdated(row,row);changed.run();}
    }
    private PullSelectionDialog(Window owner,String name,SyncOps.PullSource src,LocalScan scan,Map<String,TocInfo> localTocs,boolean push,long interfaceBytes,long wtfBytes) {
        super(owner,(push?"Push to ":"Pull from ")+name,ModalityType.APPLICATION_MODAL);
        bi.setText("Back up Interface (~"+Sizes.human(interfaceBytes)+")");bw.setText("Back up WTF (~"+Sizes.human(wtfBytes)+")");
        this.push=push;source=src.manifest();local=scan.manifest();this.localTocs=localTocs;
        sourceTocs=parse(src.tocs());catalog=new AddonCatalog(source,sourceTocs);
        for(var row:catalog.rows())(row.dataOnly()?orphans:addons).add(new Entry(row));
        Set<String> server=new HashSet<>();AddonGrouper.folders(source).forEach(f->server.add(AddonCatalog.lc(f)));
        if(!push) for(String f:AddonGrouper.folders(local))if(!server.contains(AddonCatalog.lc(f)))localOnly.add(f);
        JPanel content=new JPanel();content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));
        content.add(new JLabel(push?"Select local folders and data to upload; unrelated server content is preserved.":"Server add-ons available to pull — files and data are independent."));
        content.add(section(addons,260));
        if(!orphans.isEmpty()) {content.add(new JLabel("Orphaned / shared SavedVariables — data only; hover for all locations and provenance"));content.add(section(orphans,130));}
        if(!localOnly.isEmpty()) {
            content.add(new JLabel("Local-only add-ons absent from server — Keep by default"));
            AbstractTableModel m=new AbstractTableModel(){
                public int getRowCount(){return localOnly.size();}public int getColumnCount(){return 2;}
                public String getColumnName(int c){return c==0?"Remove (unchecked = Keep)":"Folder";}
                public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}
                public boolean isCellEditable(int row,int c){return c==0;}
                public Object getValueAt(int row,int c){return c==0?removals.contains(localOnly.get(row)):localOnly.get(row);}
                public void setValueAt(Object v,int row,int c){if(Boolean.TRUE.equals(v))removals.add(localOnly.get(row));else removals.remove(localOnly.get(row));fireTableRowsUpdated(row,row);updateSummary();}
            };
            JTable table=new JTable(m);table.setAutoCreateRowSorter(true);JScrollPane sp=new JScrollPane(table);sp.setPreferredSize(new Dimension(1050,100));content.add(sp);
            JPanel quick=new JPanel(new FlowLayout(FlowLayout.LEFT));
            for(boolean remove:new boolean[]{false,true}){JButton b=new JButton(remove?"Remove all local-only":"Keep all local-only");b.addActionListener(e->{removals.clear();if(remove)removals.addAll(localOnly);m.fireTableDataChanged();updateSummary();});quick.add(b);}content.add(quick);
        }
        JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT));options.add(general);if(!push){options.add(bi);options.add(bw);}content.add(options);
        general.addActionListener(e->updateSummary());
        if(!push)content.add(new JLabel("Backups: .wus-backups snapshots inside the version folder. Restore selected categories from Backups. Exact files previewed next."));
        JButton ok=new JButton("Preview exact changes"),cancel=new JButton("Cancel");
        ok.addActionListener(e->{
            PullSelection selected=selection();
            List<String> warnings=catalog.dependencyWarnings(selected.addons());
            if(!warnings.isEmpty()&&!Dialogs.confirmWarning(this,String.join("\n",warnings)+"\n\nContinue with this selection?"))return;
            if(!push&&(!bi.isSelected()||!bw.isSelected())&&!Dialogs.confirmWarning(this,"Backup is disabled for "+(!bi.isSelected()?"Interface ":"")+(!bw.isSelected()?"WTF":"")+". Those categories cannot be recovered from this pull's backup. Continue?"))return;
            choice=new Choice(selected,PullMode.KEEP,bi.isSelected(),bw.isSelected());dispose();
        });cancel.addActionListener(e->dispose());
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(ok);buttons.add(cancel);
        JPanel south=new JPanel(new BorderLayout());south.add(summary,BorderLayout.NORTH);south.add(buttons,BorderLayout.SOUTH);
        JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));root.add(new JScrollPane(content),BorderLayout.CENTER);root.add(south,BorderLayout.SOUTH);setContentPane(root);
        updateSummary();pack();Dimension screen=Toolkit.getDefaultToolkit().getScreenSize();setSize(Math.min(getWidth(),screen.width*95/100),Math.min(getHeight(),screen.height*90/100));setLocationRelativeTo(owner);
    }
    private JPanel section(List<Entry> entries,int height){
        Model model=new Model(entries,this::updateSummary);
        JTable table=new JTable(model){public String getToolTipText(java.awt.event.MouseEvent e){int view=rowAtPoint(e.getPoint());if(view<0)return null;var row=entries.get(convertRowIndexToModel(view)).row;return "<html>"+Dialogs.escape(row.family().source()+" | "+row.dependencies()+" | "+String.join("; ",row.locations()))+"</html>";}};
        table.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer renderer=new DefaultTableCellRenderer(){public void setValue(Object x){setText(Sizes.human(((Number)x).longValue()));}};
        table.setDefaultRenderer(Long.class,renderer);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);table.getColumnModel().getColumn(3).setPreferredWidth(200);table.getColumnModel().getColumn(5).setPreferredWidth(260);
        JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(1120,height));
        JPanel panel=new JPanel(new BorderLayout());panel.add(scroll,BorderLayout.CENTER);JPanel quick=new JPanel(new FlowLayout(FlowLayout.LEFT));
        for(String action:List.of("All files","No files","All data","No data")){JButton b=new JButton(action);b.addActionListener(e->{for(Entry x:entries){if(action.endsWith("files")&&!x.row.dataOnly())x.addon=action.startsWith("All");if(action.endsWith("data"))x.data=action.startsWith("All");}model.fireTableDataChanged();updateSummary();});quick.add(b);}JButton details=new JButton("Inspect selected row");details.addActionListener(e->{int view=table.getSelectedRow();if(view<0)return;var row=entries.get(table.convertRowIndexToModel(view)).row;ReviewDialog.info(this,"Selection details",row.name(),new String[]{"Family evidence","Dependencies","Data locations"},List.of(row.family().source()),row.dependencies().isEmpty()?List.of():Arrays.asList(row.dependencies().split(", ")),row.locations());});quick.add(details);panel.add(quick,BorderLayout.SOUTH);return panel;
    }
    private PullSelection selection(){List<String>a=new ArrayList<>(),d=new ArrayList<>(),o=new ArrayList<>();for(Entry e:addons){if(e.addon)a.add(e.row.id());if(e.data)d.add(e.row.id());}for(Entry e:orphans)if(e.data)o.add(e.row.id());return new PullSelection(a,d,general.isSelected(),new ArrayList<>(removals),o);}
    private void updateSummary(){var diff=PullPlanner.plan(source,local,selection(),sourceTocs,localTocs);summary.setText(diff.added().size()+" creates, "+diff.edited().size()+" updates, "+diff.removed().size()+" deletions; unchecked data is skipped.");}
    public static Map<String,TocInfo> parse(Map<String,byte[]> bytes){Map<String,TocInfo> out=new HashMap<>();bytes.forEach((f,b)->out.put(AddonCatalog.lc(f),TocParser.parse(f,b)));return out;}
    public static Choice show(Window owner,String name,SyncOps.PullSource src,LocalScan local){return show(owner,name,src,local,Map.of(),false);}
    public static Choice show(Window owner,String name,SyncOps.PullSource src,LocalScan local,Map<String,TocInfo> localTocs,boolean push){return show(owner,name,src,local,localTocs,push,0,0);}
    public static Choice show(Window owner,String name,SyncOps.PullSource src,LocalScan local,Map<String,TocInfo> localTocs,boolean push,long interfaceBytes,long wtfBytes){var dialog=new PullSelectionDialog(owner,name,src,local,localTocs,push,interfaceBytes,wtfBytes);dialog.setVisible(true);return dialog.choice;}
}
