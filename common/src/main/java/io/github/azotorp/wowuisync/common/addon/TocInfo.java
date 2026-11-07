package io.github.azotorp.wowuisync.common.addon;

import java.util.List;

/** Parsed fields of an addon's .toc file (DESIGN.md §15.1). */
public record TocInfo(String folder, String title, List<String> dependencies,
                      String curseId, String wagoId, String wowiId) {}
