package io.github.azotorp.wowuisync.common.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Manifest serialization: JSON, compressed with a one-byte codec prefix. */
public final class ManifestIO {
    public record ManifestJson(@JsonProperty("v") int version, @JsonProperty("e") List<ManifestEntry> entries) {}

    private ManifestIO() {}

    public static byte[] toJson(Manifest m) {
        return Json.toBytes(new ManifestJson(1, m.entries()));
    }

    public static Manifest fromJson(byte[] json) {
        ManifestJson mj = Json.fromBytes(json, ManifestJson.class);
        return new Manifest(mj.entries() == null ? List.of() : mj.entries());
    }

    /** Codec byte + compressed JSON. */
    public static byte[] encode(Manifest m) {
        return Codec.preferred().encodeTagged(toJson(m));
    }

    public static Manifest decode(byte[] data) {
        return fromJson(Codec.decodeTagged(data));
    }

    /** Writes atomically (temp file + rename). */
    public static void write(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
