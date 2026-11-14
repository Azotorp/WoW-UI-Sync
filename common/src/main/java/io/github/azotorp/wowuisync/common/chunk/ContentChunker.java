package io.github.azotorp.wowuisync.common.chunk;

import io.github.azotorp.wowuisync.common.hash.Hashing;
import java.io.*;
import java.util.*;

/** Versioned Gear CDC. Algorithm gear-v1; unsigned overflow is intentional and deterministic. */
public final class ContentChunker {
    public static final String ALGORITHM = "gear-v1";
    public static final int MIN = 512 << 10, AVG = 1 << 20, MAX = 8 << 20;
    private static final long[] GEAR = new long[256];
    static {
        long x = 0x6a09e667f3bcc909L;
        for (int i=0;i<256;i++) {
            x += 0x9e3779b97f4a7c15L;
            long z=x; z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;
            z=(z^(z>>>27))*0x94d049bb133111ebL; GEAR[i]=z^(z>>>31);
        }
    }
    @FunctionalInterface public interface Sink { void accept(ChunkRef ref, byte[] data) throws IOException; }
    public static List<ChunkRef> split(InputStream input, Sink sink) throws IOException {
        List<ChunkRef> refs=new ArrayList<>();
        byte[] chunk=new byte[MAX], buf=new byte[64<<10]; int len=0,n; long gear=0;
        while ((n=input.read(buf))!=-1) {
            for (int i=0;i<n;i++) {
                int b=buf[i]&255;chunk[len++]=(byte)b; gear=(gear<<1)+GEAR[b];
                if (len==MAX || (len>=MIN && (gear & (AVG-1))==0)) {
                    emit(chunk,len,sink,refs);len=0;gear=0;
                }
            }
        }
        if(len>0 || refs.isEmpty()) emit(chunk,len,sink,refs);
        return List.copyOf(refs);
    }
    private static void emit(byte[] chunk,int len,Sink sink,List<ChunkRef> refs) throws IOException {
        byte[] data=Arrays.copyOf(chunk,len); ChunkRef ref=new ChunkRef(Hashing.sha256Hex(data),len);
        sink.accept(ref,data); refs.add(ref);
    }
    private ContentChunker() {}
}
