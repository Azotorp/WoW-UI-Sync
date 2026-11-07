package io.github.azotorp.wowuisync.common.pack;

/** One file inside a pack: content hash and its bytes. */
public record PackEntry(String sha256, byte[] data) {}
