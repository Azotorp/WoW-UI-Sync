package io.github.azotorp.wowuisync.server.maintenance;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
/** Single process/maintenance owner; the OS releases this lock after a crash. */
public final class DataLock implements AutoCloseable {
    private final FileChannel channel;private final FileLock lock;
    private DataLock(FileChannel c,FileLock l){channel=c;lock=l;}
    public static DataLock acquire(Path root) throws IOException {
        Files.createDirectories(root);FileChannel c=FileChannel.open(root.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{FileLock l=c.tryLock();if(l==null)throw new IOException("Server data is already in use");return new DataLock(c,l);}
        catch(IOException|java.nio.channels.OverlappingFileLockException e){c.close();throw new IOException("Stop the server before maintenance or starting a second process",e);}
    }
    public static boolean running(Path root) {
        if(!Files.isDirectory(root))return false;
        try(var l=acquire(root)){return false;}catch(IOException e){return true;}
    }
    public void close() throws IOException{lock.release();channel.close();}
}
