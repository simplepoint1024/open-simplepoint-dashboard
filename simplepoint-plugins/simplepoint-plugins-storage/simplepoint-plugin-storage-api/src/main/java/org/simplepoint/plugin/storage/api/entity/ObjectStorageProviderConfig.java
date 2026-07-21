/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.simplepoint.plugin.storage.api.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;

/**
 * System-global object-storage provider configuration.
 */
@Data
@Entity
@Table(
    name = "simpoint_storage_provider_configs",
    indexes = {
        @Index(name = "idx_simpoint_storage_provider_code", columnList = "code"),
        @Index(name = "idx_simpoint_storage_provider_default", columnList = "default_provider")
    }
)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "OSS 配置", description = "系统全局对象存储连接配置")
@Schema(title = "OSS 配置", description = "系统全局对象存储连接配置")
public class ObjectStorageProviderConfig extends BaseEntityImpl<String> {

  @Column(length = 64, nullable = false)
  private String code;

  @Column(length = 128, nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "platform_type", length = 32, nullable = false)
  private ObjectStoragePlatformType type;

  @Column(length = 2048)
  private String endpoint;

  @Column(length = 128)
  private String region;

  @Column(name = "access_key", length = 512, nullable = false)
  private String accessKey;

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Transient
  private String secretKey;

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Column(name = "secret_key_ciphertext", length = 4096)
  private String secretKeyCiphertext;

  @Column(length = 255, nullable = false)
  private String bucket;

  @Column(name = "base_path", length = 512)
  private String basePath;

  @Column(name = "path_style_access")
  private Boolean pathStyleAccess;

  @Column(name = "public_base_url", length = 2048)
  private String publicBaseUrl;

  @Column(nullable = false)
  private Boolean enabled;

  @Column(name = "default_provider", nullable = false)
  private Boolean defaultProvider;

  @Column(length = 512)
  private String description;

  @Transient
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  private Boolean secretConfigured;
}
