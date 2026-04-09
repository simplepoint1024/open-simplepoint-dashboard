/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.springframework.core.annotation.Order;

/**
 * Persisted object metadata.
 */
@Data
@Entity
@Table(name = "simpoint_storage_objects")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "对象存储对象", description = "记录对象存储中的文件元信息")
@Schema(title = "对象存储对象", description = "记录对象存储中的文件元信息")
public class ObjectStorageObject extends TenantBaseEntityImpl<String> {

  @Order(0)
  @Schema(title = "i18n:storage.object.providerCode", description = "i18n:storage.object.providerCode.desc")
  @Column(name = "provider_code", length = 64, nullable = false)
  private String providerCode;

  @Order(1)
  @Schema(title = "i18n:storage.object.providerType", description = "i18n:storage.object.providerType.desc")
  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", length = 32, nullable = false)
  private ObjectStoragePlatformType providerType;

  @Order(2)
  @Schema(title = "i18n:storage.object.bucket", description = "i18n:storage.object.bucket.desc")
  @Column(length = 128, nullable = false)
  private String bucket;

  @Order(3)
  @Schema(title = "i18n:storage.object.objectKey", description = "i18n:storage.object.objectKey.desc")
  @Column(name = "object_key", length = 768, nullable = false)
  private String objectKey;

  @Order(4)
  @Schema(title = "i18n:storage.object.fileName", description = "i18n:storage.object.fileName.desc")
  @Column(name = "original_file_name", length = 255, nullable = false)
  private String originalFileName;

  @Order(5)
  @Schema(title = "i18n:storage.object.contentType", description = "i18n:storage.object.contentType.desc")
  @Column(name = "content_type", length = 255)
  private String contentType;

  @Order(6)
  @Schema(title = "i18n:storage.object.contentLength", description = "i18n:storage.object.contentLength.desc")
  @Column(name = "content_length")
  private Long contentLength;

  @Order(7)
  @Schema(title = "i18n:storage.object.eTag", description = "i18n:storage.object.eTag.desc")
  @Column(name = "etag", length = 255)
  private String eTag;

  @Order(8)
  @Schema(title = "i18n:storage.object.accessUrl", description = "i18n:storage.object.accessUrl.desc")
  @Column(name = "access_url", length = 1024)
  private String accessUrl;

  @Order(9)
  @Schema(title = "i18n:storage.object.sourceService", description = "i18n:storage.object.sourceService.desc")
  @Column(name = "source_service_name", length = 128)
  private String sourceServiceName;
}
