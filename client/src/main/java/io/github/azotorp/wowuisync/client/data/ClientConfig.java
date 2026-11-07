package io.github.azotorp.wowuisync.client.data;

import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.path.Exclusions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code client.json}: device identity, install root, per-profile exclusions, data connections, UI prefs. */
public class ClientConfig {
    public String deviceId;
    public String deviceNickname = "My PC";
    public String installRoot;
    public Map<String, ProfileSettings> profiles = new LinkedHashMap<>();
    public int dataConnections = Defaults.DATA_CONNECTIONS;
    /** {@code dark} or {@code light}. */
    public String theme = "dark";
    public String lastConnectionId;
    public String lastProfileId = "forever";

    public static class ProfileSettings {
        public List<String> exclusions = new ArrayList<>(Exclusions.DEFAULTS);
    }

    public ProfileSettings profile(String id) {
        return profiles.computeIfAbsent(id, k -> new ProfileSettings());
    }

    public Exclusions exclusions(String profileId) {
        return new Exclusions(profile(profileId).exclusions);
    }

    public static ClientConfig load(Path file) throws IOException {
        ClientConfig c;
        if (Files.isRegularFile(file)) {
            c = Json.MAPPER.readValue(Files.readAllBytes(file), ClientConfig.class);
        } else {
            c = new ClientConfig();
        }
        if (c.deviceId == null || c.deviceId.isBlank()) c.deviceId = UUID.randomUUID().toString();
        if (c.deviceNickname == null || c.deviceNickname.isBlank()) c.deviceNickname = "My PC";
        if (c.profiles == null) c.profiles = new LinkedHashMap<>();
        if (c.dataConnections < 1 || c.dataConnections > Defaults.MAX_DATA_CONNECTIONS) c.dataConnections = Defaults.DATA_CONNECTIONS;
        return c;
    }

    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.PRETTY.writeValueAsBytes(this));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
