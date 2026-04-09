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
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.api.service.ObjectStorageObjectService;
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
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;

/**
 * Admin endpoints for object storage.
 */
@RestController
@RequestMapping(ObjectStoragePaths.ADMIN_BASE)
@Tag(name = "对象存储管理", description = "用于上传文件、管理对象元信息以及租户配额")
public class ObjectStorageAdminController {

  private final ObjectStorageObjectService objectService;

  private final ObjectStorageTenantQuotaService quotaService;

  public ObjectStorageAdminController(
      final ObjectStorageObjectService objectService,
      final ObjectStorageTenantQuotaService quotaService
  ) {
    this.objectService = objectService;
    this.quotaService = quotaService;
  }

  @GetMapping("/providers")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "查询可用对象存储提供方", description = "返回当前配置且启用的对象存储提供方列表")
  public Response<?> providers() {
    return Response.okay(objectService.providers());
  }

  @GetMapping("/objects")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "分页查询对象", description = "分页查询对象存储元信息")
  public Response<Page<ObjectStorageObject>> limitObjects(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.limit(objectService.limit(attributes, pageable), ObjectStorageObject.class);
  }

  @GetMapping("/objects/{id}")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.objects.view')")
  @Operation(summary = "查询对象详情", description = "根据对象 ID 查询详细元信息")
  public Response<?> detail(@PathVariable("id") final String id) {
    return objectService.findActiveById(id)
        .<Response<?>>map(Response::okay)
        .orElseGet(() -> notFound("对象不存在: " + id));
  }

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
      return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
  }

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

  @GetMapping("/quotas")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('storage.quotas.view')")
  @Operation(summary = "分页查询租户配额", description = "查询租户对象存储配额和当前使用量")
  public Response<Page<ObjectStorageTenantQuota>> limitQuotas(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.limit(quotaService.limit(attributes, pageable), ObjectStorageTenantQuota.class);
  }

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
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }

  private Response<String> notFound(final String message) {
    return Response.of(
        ResponseEntity.status(404)
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }
}
