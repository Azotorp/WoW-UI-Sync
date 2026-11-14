package io.github.azotorp.wowuisync.client.crypto;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Passwords use the OS user vault. No plaintext fallback. Windows uses user-scoped DPAPI. */
public final class CredentialVault {
    private final Path directory;
    private final String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
    public CredentialVault(Path data) { directory=data.toAbsolutePath().resolve("credentials"); }
    private String command(List<String> args,String stdin) throws IOException {
        Process process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(OutputStream out=process.getOutputStream()) { if(stdin!=null) out.write(stdin.getBytes(StandardCharsets.UTF_8)); }
        try {
            if(!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly();throw new IOException("Credential vault timed out"); }
            byte[] bytes=process.getInputStream().readNBytes(65536);
            if(process.exitValue()!=0) throw new IOException("OS credential vault unavailable");
            return new String(bytes,StandardCharsets.UTF_8).stripTrailing();
        } catch(InterruptedException e) { Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException(e); }
    }
    private Path file(String id) { return directory.resolve(io.github.azotorp.wowuisync.common.hash.Hashing.sha256Hex(id.getBytes(StandardCharsets.UTF_8))+".dpapi"); }
    public void save(String id,String password) throws IOException {
        if(password==null) return;
        if(os.contains("win")) {
            Files.createDirectories(directory);
            String result=command(List.of("powershell.exe","-NoProfile","-NonInteractive","-Command",
                "$s=[Console]::In.ReadToEnd(); ConvertFrom-SecureString (ConvertTo-SecureString $s -AsPlainText -Force)"),password);
            io.github.azotorp.wowuisync.common.manifest.ManifestIO.write(file(id),result.getBytes(StandardCharsets.UTF_8));
        } else if(os.contains("mac")) {
            String line="add-generic-password -U -s "+quote("wow-ui-sync")+" -a "+quote(id)+" -w "+quote(password)+"\n";
            command(List.of("/usr/bin/security","-i"),line);
        } else command(List.of("secret-tool","store","--label=WoW UI Sync","service","wow-ui-sync","connection",id),password);
    }
    public String load(String id) {
        try {
            if(os.contains("win")) {
                if(!Files.exists(file(id))) return null;
                return command(List.of("powershell.exe","-NoProfile","-NonInteractive","-Command",
                    "$s=ConvertTo-SecureString ([Console]::In.ReadToEnd()); $p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s); try { [Console]::Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }"),Files.readString(file(id)));
            }
            if(os.contains("mac")) return command(List.of("/usr/bin/security","find-generic-password","-s","wow-ui-sync","-a",id,"-w"),null);
            return command(List.of("secret-tool","lookup","service","wow-ui-sync","connection",id),null);
        } catch(IOException e) { return null; }
    }
    public void remove(String id) throws IOException {
        if(os.contains("win")) Files.deleteIfExists(file(id));
        else if(os.contains("mac")) command(List.of("/usr/bin/security","delete-generic-password","-s","wow-ui-sync","-a",id),null);
        else command(List.of("secret-tool","clear","service","wow-ui-sync","connection",id),null);
    }
    private static String quote(String s) throws IOException {
        if(s.indexOf('\n')>=0 || s.indexOf('\r')>=0) throw new IOException("Keychain value cannot contain a newline");
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";
    }
}
