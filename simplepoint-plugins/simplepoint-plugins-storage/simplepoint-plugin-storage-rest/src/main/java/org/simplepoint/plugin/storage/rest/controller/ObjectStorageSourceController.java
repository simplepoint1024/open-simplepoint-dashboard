/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.plugin.storage.rest.controller;

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import org.simplepoint.plugin.storage.api.model.ObjectStorageSourceContent;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Source-bound object download for OAuth2-protected service-to-service workflows.
 */
@RestController
@RequestMapping("/internal/object-storage")
public class ObjectStorageSourceController {

  private final ObjectStorageSourceService sourceService;

  /** Creates the source-bound endpoint. */
  public ObjectStorageSourceController(final ObjectStorageSourceService sourceService) {
    this.sourceService = sourceService;
  }

  /** Downloads one object only when tenant and immutable source metadata both match. */
  @GetMapping("/sources/{id}/content")
  @PreAuthorize("hasAuthority('SCOPE_service-router.invoke')")
  public ResponseEntity<?> content(
      @PathVariable("id") final String id,
      @RequestParam("tenantId") final String tenantId,
      @RequestParam("sourceServiceName") final String sourceServiceName,
      @RequestParam(value = "maxBytes", defaultValue = "67108864") final long maxBytes
  ) {
    try {
      ObjectStorageSourceContent result = sourceService.downloadSource(
          id,
          tenantId,
          sourceServiceName,
          maxBytes
      );
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment()
                  .filename(result.fileName(), StandardCharsets.UTF_8)
                  .build()
                  .toString()
          )
          .contentType(MediaType.parseMediaType(result.contentType()))
          .contentLength(result.contentLength())
          .body(result.content());
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .contentType(MediaType.TEXT_PLAIN)
          .body(ex.getMessage());
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    }
  }
}
