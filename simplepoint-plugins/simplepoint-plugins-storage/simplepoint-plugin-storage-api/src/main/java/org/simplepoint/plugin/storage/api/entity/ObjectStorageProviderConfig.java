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

/** System-global object-storage provider configuration. */
@Data
@Entity
@Table(
    name = "simpoint_storage_provider_configs",
    indexes = {
      @Index(name = "idx_simpoint_storage_provider_code", columnList = "code"),
      @Index(name = "idx_simpoint_storage_provider_default", columnList = "default_provider")
    })
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "OSS 配置", description = "系统全局对象存储连接配置")
@Schema(title = "OSS 配置", description = "系统全局对象存储连接配置")
@org.simplepoint.core.annotation.ButtonDeclarations({
  @org.simplepoint.core.annotation.ButtonDeclaration(key = "add", title = "i18n:storage.provider.add",
      icon = "PlusCircleOutlined", sort = 0, argumentMinSize = 0, argumentMaxSize = 0,
      authority = "storage.providers.create"),
  @org.simplepoint.core.annotation.ButtonDeclaration(key = "edit", title = "i18n:table.button.edit",
      icon = "EditOutlined", sort = 1, argumentMinSize = 1, argumentMaxSize = 1,
      authority = "storage.providers.edit"),
  @org.simplepoint.core.annotation.ButtonDeclaration(key = "delete", title = "i18n:table.button.delete",
      icon = "MinusCircleOutlined", sort = 2, argumentMinSize = 1, argumentMaxSize = 10, danger = true,
      authority = "storage.providers.delete"),
  @org.simplepoint.core.annotation.ButtonDeclaration(key = "test", title = "i18n:storage.provider.test",
      icon = "SafetyOutlined", sort = 3, argumentMinSize = 1, argumentMaxSize = 1,
      authority = "storage.providers.test")
})
@org.simplepoint.core.query.TableQuery({
  @org.simplepoint.core.query.QueryField("code"),
  @org.simplepoint.core.query.QueryField("name"),
  @org.simplepoint.core.query.QueryField("type"),
  @org.simplepoint.core.query.QueryField("endpoint"),
  @org.simplepoint.core.query.QueryField("region"),
  @org.simplepoint.core.query.QueryField("bucket"),
  @org.simplepoint.core.query.QueryField("basePath"),
  @org.simplepoint.core.query.QueryField("pathStyleAccess"),
  @org.simplepoint.core.query.QueryField("publicBaseUrl"),
  @org.simplepoint.core.query.QueryField("enabled"),
  @org.simplepoint.core.query.QueryField("defaultProvider"),
  @org.simplepoint.core.query.QueryField("description"),
  @org.simplepoint.core.query.QueryField("secretConfigured"),
  @org.simplepoint.core.query.QueryField("createdAt"),
  @org.simplepoint.core.query.QueryField("updatedAt")
})
public class ObjectStorageProviderConfig extends BaseEntityImpl<String> {

  @Column(length = 64, nullable = false)
  @Schema(title = "i18n:storage.provider.code")
  private String code;

  @Column(length = 128, nullable = false)
  @Schema(title = "i18n:storage.provider.name")
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "platform_type", length = 32, nullable = false)
  @Schema(title = "i18n:storage.provider.type")
  private ObjectStoragePlatformType type;

  @Column(length = 2048)
  @Schema(title = "i18n:storage.provider.endpoint")
  private String endpoint;

  @Column(length = 128)
  @Schema(title = "i18n:storage.provider.region")
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
  @Schema(title = "i18n:storage.bucket")
  private String bucket;

  @Column(name = "base_path", length = 512)
  @Schema(title = "i18n:storage.provider.basePath")
  private String basePath;

  @Column(name = "path_style_access")
  @Schema(title = "i18n:storage.provider.pathStyleAccess")
  private Boolean pathStyleAccess;

  @Column(name = "public_base_url", length = 2048)
  @Schema(title = "i18n:storage.provider.publicBaseUrl")
  private String publicBaseUrl;

  @Column(nullable = false)
  @Schema(title = "i18n:storage.enabled")
  private Boolean enabled;

  @Column(name = "default_provider", nullable = false)
  @Schema(title = "i18n:storage.provider.default")
  private Boolean defaultProvider;

  @Column(length = 512)
  @Schema(title = "i18n:storage.description")
  private String description;

  @Transient
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @Schema(title = "i18n:storage.provider.credential")
  private Boolean secretConfigured;
}
