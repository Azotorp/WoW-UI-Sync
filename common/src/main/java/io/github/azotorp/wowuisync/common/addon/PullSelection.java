package io.github.azotorp.wowuisync.common.addon;

import java.util.List;

/** Selected top-level Interface/AddOns folder names, folders whose data is included, and the general flag. */
public record PullSelection(List<String> addons, List<String> dataAddons, boolean general) {
    public PullSelection {
        addons = addons == null ? List.of() : List.copyOf(addons);
        dataAddons = dataAddons == null ? List.of() : List.copyOf(dataAddons);
    }
}
