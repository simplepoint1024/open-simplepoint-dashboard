/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.storage.api.constants.ObjectStoragePaths;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.api.service.ObjectStorageObjectService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageProviderConfigService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageTenantQuotaService;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin endpoints for object storage.
 */
@RestController
@RequestMapping(ObjectStoragePaths.ADMIN_BASE)
@Tag(name = "对象存储管理", description = "用于上传文件、管理对象元信息以及租户配额")
public class ObjectStorageAdminController {

  private static final MediaType UTF8_TEXT_PLAIN = new MediaType(
      "text", "plain", StandardCharsets.UTF_8
  );

  private final ObjectStorageObjectService objectService;

  private final ObjectStorageTenantQuotaService quotaService;

  private final ObjectStorageProviderConfigService providerConfigService;

  /**
   * Object Storage Admin Controller.
   */
  public ObjectStorageAdminController(
      final ObjectStorageObjectService objectService,
      final ObjectStorageTenantQuotaService quotaService,
      final ObjectStorageProviderConfigService providerConfigService
  ) {
    this.objectService = objectService;
    this.quotaService = quotaService;
    this.providerConfigService = providerConfigService;
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/providers")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "查询可用对象存储提供方", description = "返回当前配置且启用的对象存储提供方列表")
  public Response<?> providers() {
    return Response.okay(objectService.providers());
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/provider-configs")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.providers.view')")
  @Operation(summary = "分页查询 OSS 配置", description = "查询系统全局 OSS 连接配置，不返回明文 Secret Key")
  public Response<Page<ObjectStorageProviderConfig>> limitProviderConfigs(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.limit(
        providerConfigService.limit(attributes, pageable),
        ObjectStorageProviderConfig.class
    );
  }

  /**
   * @ Post Mapping.
   */
  @PostMapping("/provider-configs")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.providers.create')")
  @Operation(summary = "新增 OSS 配置", description = "新增系统全局 OSS 连接并加密保存 Secret Key")
  public Response<?> addProviderConfig(@RequestBody final ObjectStorageProviderConfig data) {
    try {
      return Response.okay(providerConfigService.create(data));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * @ Put Mapping.
   */
  @PutMapping("/provider-configs")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.providers.edit')")
  @Operation(summary = "修改 OSS 配置", description = "修改系统全局 OSS 配置，Secret Key 留空时保持不变")
  public Response<?> modifyProviderConfig(@RequestBody final ObjectStorageProviderConfig data) {
    try {
      return Response.okay(providerConfigService.modifyById(data));
    } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * @ Delete Mapping.
   */
  @DeleteMapping("/provider-configs")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.providers.delete')")
  @Operation(summary = "删除 OSS 配置", description = "已被文件引用的配置不能删除")
  public Response<?> deleteProviderConfigs(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      providerConfigService.removeByIds(idSet);
      return Response.okay(idSet);
    } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * @ Post Mapping.
   */
  @PostMapping("/provider-configs/{id}/test")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.providers.test')")
  @Operation(summary = "测试 OSS 连接", description = "验证凭证和 Bucket 是否可访问")
  public Response<?> testProviderConfig(@PathVariable("id") final String id) {
    try {
      return Response.okay(providerConfigService.testConnection(id));
    } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/objects")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "分页查询对象", description = "分页查询对象存储元信息")
  public Response<Page<ObjectStorageObject>> limitObjects(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.limit(objectService.limit(attributes, pageable), ObjectStorageObject.class);
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/objects/{id}")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "查询对象详情", description = "根据对象 ID 查询详细元信息")
  public Response<?> detail(@PathVariable("id") final String id) {
    return objectService.findActiveById(id)
        .<Response<?>>map(Response::okay)
        .orElseGet(() -> notFound("对象不存在: " + id));
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/objects/{id}/content")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "下载对象内容", description = "按对象 ID 下载文件内容")
  public ResponseEntity<?> content(@PathVariable("id") final String id) {
    try {
      ObjectStorageReadResult result = objectService.download(id);
      ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment().filename(result.getFileName(), StandardCharsets.UTF_8).build().toString())
          .contentType(MediaType.parseMediaType(result.getContentType()));
      if (result.getContentLength() != null && result.getContentLength() >= 0) {
        builder.contentLength(result.getContentLength());
      }
      return builder.body(new InputStreamResource(result.getInputStream()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404).contentType(UTF8_TEXT_PLAIN).body(ex.getMessage());
    }
  }

  /**
   * @ Post Mapping.
   */
  @PostMapping(value = "/objects/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.create')")
  @Operation(summary = "上传对象", description = "上传文件到对象存储并记录元信息")
  public Response<?> upload(
      @RequestParam("file") final MultipartFile file,
      @ModelAttribute final ObjectStorageUploadRequest request
  ) {
    try {
      return Response.okay(objectService.upload(file, request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    }
  }

  /**
   * @ Delete Mapping.
   */
  @DeleteMapping("/objects")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.delete')")
  @Operation(summary = "删除对象", description = "删除对象元信息和远端文件")
  public Response<?> deleteObjects(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      objectService.removeByIds(idSet);
      return Response.okay(idSet);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    }
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/quotas")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.quotas.view')")
  @Operation(summary = "分页查询租户配额", description = "查询租户对象存储配额和当前使用量")
  public Response<Page<ObjectStorageTenantQuota>> limitQuotas(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.limit(quotaService.limit(attributes, pageable), ObjectStorageTenantQuota.class);
  }

  /**
   * @ Post Mapping.
   */
  @PostMapping("/quotas")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.quotas.create')")
  @Operation(summary = "新增租户配额", description = "为指定租户新增对象存储配额")
  public Response<?> addQuota(@RequestBody final ObjectStorageTenantQuota data) {
    try {
      return Response.okay(quotaService.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * @ Put Mapping.
   */
  @PutMapping("/quotas")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.quotas.edit')")
  @Operation(summary = "修改租户配额", description = "修改指定租户的对象存储配额")
  public Response<?> modifyQuota(@RequestBody final ObjectStorageTenantQuota data) {
    try {
      return Response.okay(quotaService.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * @ Delete Mapping.
   */
  @DeleteMapping("/quotas")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.quotas.delete')")
  @Operation(summary = "删除租户配额", description = "删除租户对象存储配额配置")
  public Response<?> deleteQuota(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      quotaService.removeByIds(idSet);
      return Response.okay(idSet);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(UTF8_TEXT_PLAIN)
            .body(message)
    );
  }

  private Response<String> notFound(final String message) {
    return Response.of(
        ResponseEntity.status(404)
            .contentType(UTF8_TEXT_PLAIN)
            .body(message)
    );
  }
}
