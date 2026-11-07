package io.github.azotorp.wowuisync.common.addon;

/** Selective pull mode (DESIGN.md §12.1). */
public enum PullMode {
    /** Local AddOns end up with exactly the selected addon folders. */
    REPLACE,
    /** Only the selection is mirrored; nothing outside it is removed. */
    KEEP
}
