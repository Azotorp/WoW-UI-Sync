package io.github.azotorp.wowuisync.common.pack;

/** Transport pack kinds (DESIGN.md §11.5). Never mixed within one pack. */
public enum PackKind {
    RAW(0),
    COMPRESSED(1);

    private final int id;

    PackKind(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static PackKind byId(int id) {
        for (PackKind k : values()) if (k.id == id) return k;
        throw new IllegalArgumentException("Unknown pack kind " + id);
    }
}
