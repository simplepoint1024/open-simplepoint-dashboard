/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Reads the declarative plugin manifest from a plugin archive.
 */
final class PluginManifestReader {

  private static final List<String> MANIFEST_LOCATIONS = List.of(
      "plugin.yaml",
      "plugin.yml",
      "plugin.json",
      "META-INF/plugin.yaml",
      "META-INF/plugin.yml",
      "META-INF/plugin.json"
  );

  private PluginManifestReader() {
  }

  static PluginManifest readRequired(JarFile jarFile) throws IOException {
    JarEntry entry = findManifestEntry(jarFile);
    if (entry == null) {
      throw new IllegalArgumentException(
          "Plugin archive must contain plugin.yaml, plugin.yml, plugin.json, or META-INF/plugin.yaml");
    }
    File manifestFile = copyEntryToTempFile(jarFile, entry);
    try {
      PluginManifest manifest =
          ConfigReader.Companion.read(manifestFile, PluginManifest.class);
      PluginManifestValidator.validate(manifest);
      return manifest;
    } finally {
      Files.deleteIfExists(manifestFile.toPath());
    }
  }

  private static JarEntry findManifestEntry(JarFile jarFile) {
    for (String location : MANIFEST_LOCATIONS) {
      JarEntry entry = jarFile.getJarEntry(location);
      if (entry != null && !entry.isDirectory()) {
        return entry;
      }
    }
    return null;
  }

  private static File copyEntryToTempFile(JarFile jarFile, JarEntry entry) throws IOException {
    String suffix = entry.getName().endsWith(".json") ? ".json" : ".yaml";
    File tempFile = File.createTempFile("simplepoint-plugin-manifest-", suffix);
    try (var input = jarFile.getInputStream(entry)) {
      Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    return tempFile;
  }
}
