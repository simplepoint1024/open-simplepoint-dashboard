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
import java.util.NoSuchElementException;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.storage.api.constants.ObjectStoragePaths;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.api.service.ObjectStorageObjectService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping(ObjectStoragePaths.REMOTE_BASE)
@Tag(name = "对象存储服务接口", description = "提供给其他服务上传文件并获取元信息")
public class ObjectStorageServiceController {

  private final ObjectStorageObjectService objectService;

  public ObjectStorageServiceController(final ObjectStorageObjectService objectService) {
    this.objectService = objectService;
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "服务上传对象", description = "供其他服务通过 HTTP 上传对象并获取元信息")
  public Response<?> upload(
      @RequestParam("file") final MultipartFile file,
      @ModelAttribute final ObjectStorageUploadRequest request
  ) {
    try {
      return Response.okay(objectService.upload(file, request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return Response.of(ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage()));
    }
  }

  @GetMapping("/objects/{id}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "服务查询对象元信息", description = "供其他服务根据对象 ID 查询元信息")
  public Response<?> detail(@PathVariable("id") final String id) {
    return objectService.findActiveById(id)
        .<Response<?>>map(Response::okay)
        .orElseGet(() -> Response.of(ResponseEntity.status(404)
            .contentType(MediaType.TEXT_PLAIN)
            .body("对象不存在: " + id)));
  }
}
