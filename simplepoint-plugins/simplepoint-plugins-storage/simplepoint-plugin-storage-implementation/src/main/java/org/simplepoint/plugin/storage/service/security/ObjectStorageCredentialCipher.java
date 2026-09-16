/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.service.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts object-storage secret keys using AES-GCM before persistence.
 */
@Component
public class ObjectStorageCredentialCipher {

  private static final String PREFIX = "v1:";

  private static final int IV_LENGTH = 12;

  private static final int TAG_LENGTH_BITS = 128;

  private final ObjectStorageProperties properties;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Creates the credential cipher.
   *
   * @param properties object-storage properties
   */
  public ObjectStorageCredentialCipher(final ObjectStorageProperties properties) {
    this.properties = properties;
  }

  /**
   * Encrypts a plaintext secret key.
   *
   * @param plaintext secret key
   * @return versioned ciphertext
   */
  public String encrypt(final String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
      payload.put(iv).put(encrypted);
      return PREFIX + Base64.getEncoder().encodeToString(payload.array());
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("OSS 凭证加密失败", ex);
    }
  }

  /**
   * Decrypts a persisted secret key.
   *
   * @param ciphertext versioned ciphertext
   * @return plaintext secret key
   */
  public String decrypt(final String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      return null;
    }
    if (!ciphertext.startsWith(PREFIX)) {
      throw new IllegalStateException("无法识别的 OSS 凭证密文版本");
    }
    try {
      byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
      if (payload.length <= IV_LENGTH) {
        throw new IllegalStateException("OSS 凭证密文无效");
      }
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      byte[] iv = new byte[IV_LENGTH];
      buffer.get(iv);
      byte[] encrypted = new byte[buffer.remaining()];
      buffer.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("OSS 凭证解密失败，请检查加密主密钥", ex);
    }
  }

  private SecretKeySpec key() throws GeneralSecurityException {
    String configured = properties.getCredentialEncryptionKey();
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException(
          "未配置 simplepoint.storage.credential-encryption-key，无法保存或使用 OSS 凭证"
      );
    }
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(configured.getBytes(StandardCharsets.UTF_8));
    return new SecretKeySpec(digest, "AES");
  }
}
