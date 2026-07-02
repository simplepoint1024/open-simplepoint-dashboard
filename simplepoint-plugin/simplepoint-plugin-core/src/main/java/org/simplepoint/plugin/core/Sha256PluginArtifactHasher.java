/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.simplepoint.plugin.api.PluginArtifact;

/**
 * Computes SHA-256 metadata for file-based plugin artifacts.
 */
final class Sha256PluginArtifactHasher implements PluginArtifactHasher {

  private static final int BUFFER_SIZE = 8192;
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  @Override
  public PluginArtifact hash(URI uri) throws IOException {
    Path path = Path.of(uri);
    MessageDigest digest = sha256();
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return new PluginArtifact(uri, Files.size(path), hex(digest.digest()));
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is not available", e);
    }
  }

  private String hex(byte[] digest) {
    char[] value = new char[digest.length * 2];
    for (int i = 0; i < digest.length; i++) {
      int item = digest[i] & 0xff;
      value[i * 2] = HEX[item >>> 4];
      value[i * 2 + 1] = HEX[item & 0x0f];
    }
    return new String(value);
  }
}
