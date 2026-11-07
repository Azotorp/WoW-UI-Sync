package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.common.manifest.Manifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Result of scanning a flavor folder: the manifest of synced files, their absolute paths by case-insensitive key,
 * paths skipped for invalid names, and paths that differ only by case.
 */
public record LocalScan(Path flavorPath, Manifest manifest, Map<String, Path> files, List<String> skippedInvalid,
                        List<List<String>> caseCollisions) {}
