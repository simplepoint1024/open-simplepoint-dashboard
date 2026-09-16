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
import java.util.NoSuchElementException;
import java.util.Set;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.storage.api.constants.ObjectStoragePaths;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.api.service.ObjectStorageObjectService;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.springframework.core.io.InputStreamResource;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service-facing HTTP API for object storage.
 */
@RestController
@RequestMapping({ObjectStoragePaths.GLOBAL_BASE, ObjectStoragePaths.REMOTE_BASE})
@Tag(name = "统一对象存储接口", description = "系统内统一上传文件并获取租户隔离的元信息")
public class ObjectStorageServiceController {

  private static final MediaType UTF8_TEXT_PLAIN = new MediaType(
      "text", "plain", StandardCharsets.UTF_8
  );

  private final ObjectStorageObjectService objectService;

  /**
   * Object Storage Service Controller.
   */
  public ObjectStorageServiceController(final ObjectStorageObjectService objectService) {
    this.objectService = objectService;
  }

  /**
   * @ Post Mapping.
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "统一上传对象", description = "使用系统默认 OSS 配置并按当前租户隔离存储")
  public Response<?> upload(
      @RequestParam("file") final MultipartFile file,
      @ModelAttribute final ObjectStorageUploadRequest request
  ) {
    try {
      return Response.okay(objectService.upload(file, request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(ResponseEntity.badRequest().contentType(UTF8_TEXT_PLAIN).body(ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return Response.of(ResponseEntity.status(404).contentType(UTF8_TEXT_PLAIN).body(ex.getMessage()));
    }
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/objects/{id}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "查询对象元信息", description = "仅返回当前租户拥有的对象元信息")
  public Response<?> detail(@PathVariable("id") final String id) {
    return objectService.findActiveById(id)
        .<Response<?>>map(Response::okay)
        .orElseGet(() -> Response.of(ResponseEntity.status(404)
            .contentType(UTF8_TEXT_PLAIN)
            .body("对象不存在: " + id)));
  }

  /**
   * Downloads one tenant-owned object.
   */
  @GetMapping("/objects/{id}/content")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "下载对象内容", description = "下载当前租户拥有的对象")
  public ResponseEntity<?> content(@PathVariable("id") final String id) {
    try {
      ObjectStorageReadResult result = objectService.download(id);
      ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment()
                  .filename(result.getFileName(), StandardCharsets.UTF_8)
                  .build()
                  .toString()
          )
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
   * Renders an authenticated OSS image inline. This endpoint intentionally does
   * not depend on the active tenant so user avatars remain visible after a
   * workspace switch.
   */
  @GetMapping("/images/{id}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "显示 OSS 图片", description = "以内联方式显示头像和 JSON Schema 图片")
  public ResponseEntity<?> image(@PathVariable("id") final String id) {
    try {
      ObjectStorageReadResult result = objectService.downloadImage(id);
      ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.inline()
                  .filename(result.getFileName(), StandardCharsets.UTF_8)
                  .build()
                  .toString()
          )
          .contentType(MediaType.parseMediaType(result.getContentType()))
          .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
      if (result.getContentLength() != null && result.getContentLength() >= 0) {
        builder.contentLength(result.getContentLength());
      }
      return builder.body(new InputStreamResource(result.getInputStream()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404).contentType(UTF8_TEXT_PLAIN).body(ex.getMessage());
    }
  }

  /**
   * Deletes one tenant-owned object.
   */
  @DeleteMapping("/objects/{id}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "删除对象", description = "删除当前租户拥有的对象及元信息")
  public Response<?> delete(@PathVariable("id") final String id) {
    if (objectService.findActiveById(id).isEmpty()) {
      return Response.of(ResponseEntity.status(404)
          .contentType(UTF8_TEXT_PLAIN)
          .body("对象不存在: " + id));
    }
    objectService.removeByIds(Set.of(id));
    return Response.okay(id);
  }
}
