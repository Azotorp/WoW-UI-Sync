package io.github.azotorp.wowuisync.client.local;

import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Native per-folder Windows copy, then bounded verification against freshly validated cached hashes. */
final class BackupCopier {
    record Stamp(long size,String modified,String identity) {}
    record Entry(String relative,Path source,Stamp stamp) {}
    record Inventory(List<Entry> files,List<String> directories) {}
    private static final ThreadLocal<byte[]> BUFFER=ThreadLocal.withInitial(()->new byte[256*1024]);
    static void check(BooleanSupplier canceled) throws InterruptedIOException {
        if(canceled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Backup canceled; live folders unchanged");
    }
    static Stamp stamp(Path file,BasicFileAttributes attrs,boolean unix) throws IOException {
        String identity=String.valueOf(attrs.fileKey())+":"+attrs.creationTime();
        if(unix)identity+=":"+Files.getAttribute(file,"unix:ctime",LinkOption.NOFOLLOW_LINKS);
        return new Stamp(attrs.size(),attrs.lastModifiedTime().toString(),identity);
    }
    static Inventory inventory(Path root,boolean iface,boolean wtf,boolean unix,BooleanSupplier canceled) throws IOException {
        List<Entry> files=new ArrayList<>();List<String> directories=new ArrayList<>();
        for(String category:List.of("Interface","WTF")) {
            if(category.equals("Interface")?!iface:!wtf)continue;
            Path source=root.resolve(category);
            if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Backup category is not a regular directory");
            Files.walkFileTree(source,new SimpleFileVisitor<>() {
                public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) throws IOException {
                    check(canceled);directories.add(root.relativize(dir).toString().replace('\\','/'));return FileVisitResult.CONTINUE;
                }
                public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                    check(canceled);if(!attrs.isRegularFile())throw new IOException("Backup refuses symlinks and special files");
                    files.add(new Entry(root.relativize(file).toString().replace('\\','/'),file,stamp(file,attrs,unix)));return FileVisitResult.CONTINUE;
                }
            });
        }
        files.sort(Comparator.comparing(Entry::relative));directories.sort(String::compareTo);
        return new Inventory(List.copyOf(files),List.copyOf(directories));
    }
    static boolean cacheMatches(ClientDb.ScanCache cached,Stamp state,long now) {
        return cached!=null&&Hashing.isSha256Hex(cached.sha256())&&cached.size()==state.size()
                &&cached.modified().equals(state.modified())&&cached.identity().equals(state.identity())
                &&now>=cached.verifiedAt()&&now-cached.verifiedAt()<24*60*60*1000L
                &&java.time.Instant.parse(state.modified()).toEpochMilli()<=now+2000;
    }
    static Path nativeTool() {
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))return null;
        String windows=System.getenv("SystemRoot");if(windows==null)windows=System.getenv("WINDIR");
        if(windows==null)return null;
        Path executable=Path.of(windows,"System32","robocopy.exe");
        return Files.isRegularFile(executable)?executable:null;
    }
    static List<String> command(Path tool,Path source,Path target,Path log,int workers) {
        return List.of(tool.toString(),source.toString(),target.toString(),"/E","/COPY:DAT","/DCOPY:DAT","/XJ",
                "/MT:"+workers,"/R:0","/W:0","/NFL","/NDL","/NP","/NJH","/NJS","/LOG:"+log);
    }
    static boolean successfulExit(int code){return code>=0&&code<8;}
    static void nativeCopy(Path tool,Path source,Path target,Path log,int workers,BooleanSupplier canceled) throws IOException {
        check(canceled);
        Process process=new ProcessBuilder(command(tool,source,target,log,workers)).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            while(!process.waitFor(100,TimeUnit.MILLISECONDS))check(canceled);
            check(canceled);
            if(!successfulExit(process.exitValue()))throw new IOException("Native folder backup failed (Robocopy exit "+process.exitValue()+"); live folders unchanged");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Backup interrupted");}
        finally {
            if(process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted=Thread.interrupted();
                try {if(!process.waitFor(5,TimeUnit.SECONDS))throw new IOException("Native backup worker did not stop; do not use the incomplete snapshot");}
                catch(InterruptedException e){interrupted=true;}
                finally {if(interrupted)Thread.currentThread().interrupt();}
            }
        }
    }
    interface Work {void run(int index) throws IOException;}
    static void parallel(int count,int workers,Work work,BooleanSupplier canceled) throws IOException {
        ExecutorService pool=Executors.newFixedThreadPool(workers);AtomicInteger index=new AtomicInteger();
        AtomicReference<IOException> failure=new AtomicReference<>();List<Future<?>> tasks=new ArrayList<>();
        try {
            for(int worker=0;worker<workers;worker++)tasks.add(pool.submit(()->{
                while(failure.get()==null){int next=index.getAndIncrement();if(next>=count)return;try{check(canceled);work.run(next);}catch(IOException e){failure.compareAndSet(null,e);return;}}
            }));
            for(Future<?> task:tasks)task.get();
            if(failure.get()!=null)throw failure.get();check(canceled);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Backup interrupted");}
        catch(ExecutionException e){throw new IOException("Backup worker failed",e.getCause());}
        finally {
            pool.shutdownNow();boolean interrupted=Thread.interrupted();
            try {while(!pool.isTerminated()){try{pool.awaitTermination(250,TimeUnit.MILLISECONDS);}catch(InterruptedException e){interrupted=true;}}}
            finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    static String hash(Path path,BooleanSupplier canceled) throws IOException {
        var digest=Hashing.newSha256();byte[] buffer=BUFFER.get();
        try(InputStream input=Files.newInputStream(path)){int read;while((read=input.read(buffer))!=-1){check(canceled);digest.update(buffer,0,read);}}
        return Hashing.hex(digest.digest());
    }
    static Map<String,String> copy(Path root,Path stage,boolean iface,boolean wtf,ClientDb db,
                                    Backups.Progress progress,BooleanSupplier canceled,long total,long bytes) throws IOException,java.sql.SQLException {
        check(canceled);root=root.toRealPath();final Path install=root;
        boolean unix=Files.getFileStore(root).supportsFileAttributeView("unix");
        Inventory before=inventory(root,iface,wtf,unix,canceled);
        if(before.files().size()!=total||before.files().stream().mapToLong(e->e.stamp().size()).sum()!=bytes)throw new IOException("Live files changed before backup; retry");
        Map<String,ClientDb.ScanCache> cache=db.scanCache(root.toString());
        long now=System.currentTimeMillis();int workers=Math.max(1,Math.min(8,Integer.getInteger("wowuisync.backupThreads",Runtime.getRuntime().availableProcessors())));
        String backend=System.getProperty("wowuisync.backupBackend","auto");
        Path nativeTool=backend.equals("java")?null:nativeTool();
        if(backend.equals("native")&&nativeTool==null)throw new IOException("Windows Robocopy is unavailable");
        if(nativeTool!=null) {
            for(String category:List.of("Interface","WTF")) {
                if(category.equals("Interface")?!iface:!wtf)continue;
                Path source=root.resolve(category);if(!Files.exists(source))continue;
                if(progress!=null)progress.file("Copying folder (Windows native)",category,-1,-1,-1,-1);
                Path log=stage.resolve(".robocopy-"+category+".log");
                nativeCopy(nativeTool,source,stage.resolve(category),log,workers,canceled);Files.deleteIfExists(log);
            }
        } else {
            for(String directory:before.directories())Files.createDirectories(stage.resolve(directory));
            AtomicLong done=new AtomicLong(),copiedBytes=new AtomicLong();Object notification=new Object();
            parallel(before.files().size(),workers,index->{
                Entry file=before.files().get(index);Files.copy(file.source(),stage.resolve(file.relative()),StandardCopyOption.COPY_ATTRIBUTES);
                synchronized(notification){long d=done.incrementAndGet(),b=copiedBytes.addAndGet(file.stamp().size());if(progress!=null)progress.file("Copying backup",file.relative(),d,total,b,bytes);}
            },canceled);
        }
        if(progress!=null)progress.file("Verifying backup","",0,total,0,bytes);
        Map<String,String> hashes=new ConcurrentHashMap<>();AtomicInteger cacheHits=new AtomicInteger();AtomicLong verified=new AtomicLong(),verifiedBytes=new AtomicLong();Object notification=new Object();
        parallel(before.files().size(),workers,index->{
            Entry entry=before.files().get(index);Path target=stage.resolve(entry.relative());
            Stamp current=stamp(entry.source(),Files.readAttributes(entry.source(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),unix);
            if(!entry.stamp().equals(current))throw new IOException("A live file changed during backup; close external tools and retry");
            var targetAttrs=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!targetAttrs.isRegularFile()||targetAttrs.size()!=entry.stamp().size())throw new IOException("Backup is incomplete");
            ClientDb.ScanCache cached=cache.get(entry.relative());
            boolean cachedValid=cacheMatches(cached,entry.stamp(),now);if(cachedValid)cacheHits.incrementAndGet();
            String expected=cachedValid?cached.sha256():hash(entry.source(),canceled);
            String actual=hash(target,canceled);
            if(!expected.equals(actual)||!entry.stamp().equals(stamp(entry.source(),Files.readAttributes(entry.source(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),unix)))throw new IOException("Backup content verification failed; live folders unchanged");
            hashes.put(entry.relative(),actual);
            synchronized(notification){long d=verified.incrementAndGet(),b=verifiedBytes.addAndGet(entry.stamp().size());if(progress!=null)progress.file("Verifying backup",entry.relative(),d,total,b,bytes);}
        },canceled);
        Inventory after=inventory(install,iface,wtf,unix,canceled);
        if(!before.equals(after))throw new IOException("Live folders changed during backup; retry");
        Inventory copied=inventory(stage,iface,wtf,unix,canceled);
        if(!before.directories().equals(copied.directories())||!hashes.keySet().equals(new HashSet<>(copied.files().stream().map(Entry::relative).toList())))throw new IOException("Backup contains missing or extra paths");
        io.github.azotorp.wowuisync.client.util.ClientLog.LOG.info("Backup copy verified: backend="+(nativeTool==null?"java-parallel":"windows-robocopy")+", cached source hashes="+cacheHits.get()+"/"+total+", destination bytes verified="+bytes);
        return new TreeMap<>(hashes);
    }
}
