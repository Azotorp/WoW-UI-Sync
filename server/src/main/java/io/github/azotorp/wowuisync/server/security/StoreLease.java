package io.github.azotorp.wowuisync.server.security;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
/** Process-wide exclusive lease acquired before configuration, logs, DB or keys are changed. */
public final class StoreLease implements AutoCloseable {
    private final FileChannel channel;private final FileLock lock;
    private StoreLease(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
    public static StoreLease acquire(Path root) throws IOException {
        Files.createDirectories(root);
        FileChannel channel=FileChannel.open(root.resolve(".store.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            FileLock lock=channel.tryLock();
            if(lock==null)throw new IOException("Data directory is in use by another server; choose a separate test store");
            return new StoreLease(channel,lock);
        } catch(IOException|OverlappingFileLockException e) { channel.close();throw new IOException("Data directory is already locked or inaccessible",e); }
    }
    public void close() throws IOException { try{lock.release();}finally{channel.close();} }
}
