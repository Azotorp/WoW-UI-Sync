package io.github.azotorp.wowuisync.common.addon;
import java.util.List;
/** Protocol 2: unchecked is skip; deletion requires an explicit folder in removeAddons. */
public record PullSelection(List<String> addons, List<String> dataAddons, boolean general,
                            List<String> removeAddons, List<String> orphanData) {
    public PullSelection(List<String> addons, List<String> dataAddons, boolean general) {
        this(addons,dataAddons,general,List.of(),List.of());
    }
    public PullSelection {
        addons = addons == null ? List.of() : List.copyOf(addons);
        dataAddons = dataAddons == null ? List.of() : List.copyOf(dataAddons);
        removeAddons = removeAddons == null ? List.of() : List.copyOf(removeAddons);
        orphanData = orphanData == null ? List.of() : List.copyOf(orphanData);
    }
}
