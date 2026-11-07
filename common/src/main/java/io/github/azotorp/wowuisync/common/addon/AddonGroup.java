package io.github.azotorp.wowuisync.common.addon;

import java.util.List;

/** An addon as the user sees it: a main folder plus folders grouped with it. */
public record AddonGroup(String mainFolder, String displayName, List<String> folders) {}
