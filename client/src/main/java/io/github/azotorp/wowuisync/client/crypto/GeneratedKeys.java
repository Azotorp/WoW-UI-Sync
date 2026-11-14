package io.github.azotorp.wowuisync.client.crypto;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;
public final class GeneratedKeys {
    public static Path create(Path directory) throws Exception {
        Files.createDirectories(directory);
        KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path key=directory.resolve("device-"+java.util.UUID.randomUUID()+".pem");
        String pem="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";
        if(Files.getFileAttributeView(directory,java.nio.file.attribute.PosixFileAttributeView.class)!=null) {
            Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.createFile(key,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } else {
            var view=Files.getFileAttributeView(directory,java.nio.file.attribute.AclFileAttributeView.class);
            if(view==null) throw new java.io.IOException("Cannot secure private-key directory on this filesystem");
            var owner=Files.getOwner(directory);
            view.setAcl(java.util.List.of(java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                .setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT).build()));
            Files.createFile(key);
        }
        Files.writeString(key,pem);return key;
    }
    private GeneratedKeys() {}
}
