package io.github.azotorp.wowuisync.client.wow;

import io.github.azotorp.wowuisync.common.profile.Profiles;

import java.nio.file.Path;

/** A detected flavor folder (DESIGN.md §13). {@code valid} = has WoW markers; {@code enabled} = syncable in v1. */
public record Flavor(String folderName, Path path, String profileId, boolean valid) {
    public boolean enabled() {
        return valid && Profiles.isEnabled(profileId);
    }

    public String displayName() {
        return Profiles.displayName(profileId);
    }

    @Override
    public String toString() {
        String s = displayName() + " (" + folderName + ")";
        if (!valid) return s + " - not a WoW install?";
        if (!enabled()) return s + " - coming later";
        return s;
    }
}
