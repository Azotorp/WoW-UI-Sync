package io.github.azotorp.wowuisync.common.addon;

import java.util.List;

/** An addon as the user sees it: one independently selectable top-level folder (the list contains exactly one entry). */
public record AddonGroup(String mainFolder, String displayName, List<String> folders) {}
