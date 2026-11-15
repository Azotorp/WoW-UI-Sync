package io.github.azotorp.wowuisync.common.addon;
import java.util.List;
/** Metadata fields stay independent: dependencies never imply family membership. */
public record TocInfo(String folder, String title, List<String> dependencies,
        String curseId, String wagoId, String wowiId, String group,
        List<String> optionalDependencies, List<String> savedVariables, List<String> perCharacterVariables) {
    public TocInfo(String folder, String title, List<String> dependencies, String curseId, String wagoId, String wowiId) {
        this(folder, title, dependencies, curseId, wagoId, wowiId, null, List.of(), List.of(), List.of());
    }
}
