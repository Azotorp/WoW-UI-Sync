package io.github.azotorp.wowuisync.common.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.azotorp.wowuisync.common.json.Json;

import java.util.Base64;

/** Invite code format (DESIGN.md §8): {@code wus1-} + base64url(JSON {h, p, f, s}). */
public record InviteCode(@JsonProperty("h") String host, @JsonProperty("p") int port,
                         @JsonProperty("f") String fingerprint, @JsonProperty("s") String secret) {
    public static final String PREFIX = "wus1-";

    public String encode() {
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Json.toBytes(this));
    }

    public static InviteCode decode(String code) {
        String c = code == null ? "" : code.trim().replaceAll("\\s+", "");
        if (!c.startsWith(PREFIX)) throw new IllegalArgumentException("Invite codes start with " + PREFIX);
        try {
            byte[] json = Base64.getUrlDecoder().decode(c.substring(PREFIX.length()));
            InviteCode ic = Json.fromBytes(json, InviteCode.class);
            if (ic.host() == null || ic.secret() == null || ic.port() <= 0) throw new IllegalArgumentException("Incomplete code");
            return ic;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("This invite code is not valid.", e);
        }
    }

}
