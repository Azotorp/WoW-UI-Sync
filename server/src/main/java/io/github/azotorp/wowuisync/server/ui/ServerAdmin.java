package io.github.azotorp.wowuisync.server.ui;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.server.config.*;
import io.github.azotorp.wowuisync.server.maintenance.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.sql.*;

/** Optional local desktop console. Headless run never initializes Swing. */
public final class ServerAdmin {
    public static void show(DataLayout layout) throws Exception {
        if(GraphicsEnvironment.isHeadless())throw new java.io.IOException("No desktop display. Use run, status, verify, migrate and gc; remote administration is available in the client.");
        if(!Files.getOwner(layout.root()).equals(Files.getOwner(Path.of(System.getProperty("user.home")))))throw new java.io.IOException("Run the desktop console as the data directory owner");
        SwingUtilities.invokeLater(()->{
            JFrame frame=new JFrame(Version.APP_NAME+" · server "+Version.APP_VERSION);frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JTextArea output=new JTextArea(22,100);output.setEditable(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
            var wallpaper=new java.util.concurrent.atomic.AtomicBoolean(!"false".equals(System.getProperty("wowuisync.wallpaper")));
            JPanel root=new io.github.azotorp.wowuisync.common.ui.WallpaperPanel(new BorderLayout(12,12),"/artwork/server.png",wallpaper::get,()->25);
            root.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));frame.setContentPane(root);
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));JCheckBox art=new JCheckBox("Background",wallpaper.get());art.addActionListener(e->{wallpaper.set(art.isSelected());root.repaint();});buttons.add(art);
            JButton refresh=new JButton("Refresh"),start=new JButton("Start"),stop=new JButton("Stop"),verify=new JButton("Verify offline"),logs=new JButton("View log");
            for(JButton b:new JButton[]{refresh,start,stop,verify,logs})buttons.add(b);
            Runnable reload=()->new SwingWorker<String,Void>() {
                protected String doInBackground() throws Exception {
                    StringBuilder text=new StringBuilder("State: "+(DataLock.running(layout.root())?"running":"stopped")+"\nData: "+layout.root()+"\nProtocol: "+Version.PROTOCOL_VERSION+" · schema: "+io.github.azotorp.wowuisync.server.db.ServerDb.SCHEMA_VERSION+"\n\n");
                    try(var c=DriverManager.getConnection("jdbc:sqlite:file:"+layout.db()+"?mode=ro");var s=c.createStatement();var r=s.executeQuery("SELECT username,role,quota_mb,created_at FROM accounts ORDER BY username")) {
                        while(r.next())text.append(r.getString(1)).append(" · ").append(r.getString(2)).append(" · quota MB ").append(r.getObject(3)).append('\n');
                    }
                    text.append("\nInvite, account, credential and quota changes require login in the client's Accounts panel.\nSnapshots, labels and retention are managed in the client's Snapshots panel.\nUpdates require a verified local release and an offline migration checkpoint.\n");return text.toString();
                }
                protected void done(){try{output.setText(get());}catch(Exception e){output.setText(e.getMessage());}}
            }.execute();
            refresh.addActionListener(e->reload.run());
            start.addActionListener(e->{try{
                if(DataLock.running(layout.root()))throw new java.io.IOException("Already running");
                Path jar=Path.of(ServerAdmin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if(!Files.isRegularFile(jar))throw new java.io.IOException("Run the packaged JAR");
                new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),"run","--data",layout.root().toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(layout.logs().resolve("desktop-launch.log").toFile())).start();reload.run();
            }catch(Exception x){JOptionPane.showMessageDialog(frame,x.getMessage());}});
            stop.addActionListener(e->{if(JOptionPane.showConfirmDialog(frame,"Stop the local server?","Stop",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try{Files.writeString(layout.stopFlag(),"stop\n");reload.run();}catch(Exception x){output.setText(x.getMessage());}});
            verify.addActionListener(e->new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{if(DataLock.running(layout.root()))throw new java.io.IOException("Stop the server first");RepositoryMaintenance.verify(layout);return null;}protected void done(){try{get();JOptionPane.showMessageDialog(frame,"Integrity verification passed");}catch(Exception x){JOptionPane.showMessageDialog(frame,x.getMessage());}}}.execute());
            logs.addActionListener(e->{try{String text=Files.readString(layout.logs().resolve("server.log"));output.setText(text.substring(Math.max(0,text.length()-100_000)));}catch(Exception x){output.setText(x.getMessage());}});
            frame.add(new JLabel("Local owner console · use systemctl for a production service"),BorderLayout.NORTH);frame.add(new JScrollPane(output),BorderLayout.CENTER);frame.add(buttons,BorderLayout.SOUTH);frame.pack();frame.setLocationRelativeTo(null);frame.setVisible(true);reload.run();
        });
    }
    private ServerAdmin(){}
}
