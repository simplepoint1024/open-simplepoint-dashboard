/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.service;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.plugin.storage.api.constants.ObjectStoragePaths;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.client.model.ObjectStorageRemoteContent;
import org.simplepoint.plugin.storage.client.properties.ObjectStorageRemoteProperties;
import org.simplepoint.plugin.storage.client.support.NamedInputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

/**
 * RestClient-based implementation of the remote object-storage client.
 */
public class RestClientObjectStorageRemoteService implements ObjectStorageRemoteService {

  private final RestClient restClient;

  /**
   * Rest Client Object Storage Remote Service.
   */
  public RestClientObjectStorageRemoteService(
      final RestClient.Builder restClientBuilder,
      final ObjectStorageRemoteProperties properties
  ) {
    this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
  }

  @Override
  public ObjectStorageObject upload(final MultipartFile file, final ObjectStorageUploadRequest request) {
    try {
      return upload(
          new NamedInputStreamResource(file.getInputStream(), resolveFileName(file, request), file.getSize()),
          resolveFileName(file, request),
          file.getContentType(),
          file.getSize(),
          request
      );
    } catch (IOException ex) {
      throw new IllegalStateException("读取上传文件失败: " + ex.getMessage(), ex);
    }
  }

  @Override
  public ObjectStorageObject upload(
      final Resource resource,
      final String fileName,
      final String contentType,
      final long contentLength,
      final ObjectStorageUploadRequest request
  ) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType(contentType == null ? "application/octet-stream" : contentType));
    if (contentLength >= 0) {
      fileHeaders.setContentLength(contentLength);
    }
    form.add("file", new HttpEntity<>(resource, fileHeaders));
    addIfPresent(form, "providerCode", request == null ? null : request.getProviderCode());
    addIfPresent(form, "directory", request == null ? null : request.getDirectory());
    addIfPresent(form, "objectKey", request == null ? null : request.getObjectKey());
    addIfPresent(form, "fileName", fileName);
    addIfPresent(form, "sourceServiceName", request == null ? null : request.getSourceServiceName());
    try {
      return restClient.post()
          .uri(ObjectStoragePaths.GLOBAL_BASE + "/upload")
          .headers(this::copyContextHeaders)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(form)
          .retrieve()
          .body(ObjectStorageObject.class);
    } catch (RestClientResponseException ex) {
      throw remoteFailure("上传", ex);
    }
  }

  @Override
  public Optional<ObjectStorageObject> metadata(final String id) {
    try {
      ObjectStorageObject body = restClient.get()
          .uri(ObjectStoragePaths.GLOBAL_BASE + "/objects/{id}", id)
          .headers(this::copyContextHeaders)
          .retrieve()
          .body(ObjectStorageObject.class);
      return Optional.ofNullable(body);
    } catch (HttpClientErrorException.NotFound ex) {
      return Optional.empty();
    } catch (RestClientResponseException ex) {
      throw remoteFailure("查询", ex);
    }
  }

  @Override
  public ObjectStorageRemoteContent download(final String id) {
    ResponseEntity<byte[]> response;
    try {
      response = restClient.get()
          .uri(ObjectStoragePaths.GLOBAL_BASE + "/objects/{id}/content", id)
          .headers(this::copyContextHeaders)
          .retrieve()
          .toEntity(byte[].class);
    } catch (RestClientResponseException ex) {
      throw remoteFailure("下载", ex);
    }
    byte[] content = response.getBody() == null ? new byte[0] : response.getBody();
    MediaType contentType = response.getHeaders().getContentType();
    return new ObjectStorageRemoteContent(
        content,
        response.getHeaders().getContentDisposition().getFilename(),
        contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType.toString(),
        response.getHeaders().getContentLength() < 0
            ? content.length : response.getHeaders().getContentLength()
    );
  }

  @Override
  public void delete(final String id) {
    try {
      restClient.delete()
          .uri(ObjectStoragePaths.GLOBAL_BASE + "/objects/{id}", id)
          .headers(this::copyContextHeaders)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound ex) {
      // Deletion is intentionally idempotent for cross-service cleanup and retries.
    } catch (RestClientResponseException ex) {
      throw remoteFailure("删除", ex);
    }
  }

  private void copyContextHeaders(final HttpHeaders headers) {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes requestAttributes)) {
      return;
    }
    HttpServletRequest request = requestAttributes.getRequest();
    copyHeader(request, headers, HttpHeaders.AUTHORIZATION);
    copyHeader(request, headers, "X-Tenant-Id");
    copyHeader(request, headers, "X-Context-Id");
    copyHeader(request, headers, HttpHeaders.ACCEPT_LANGUAGE);
    copyHeader(request, headers, HttpHeaders.COOKIE);
  }

  private static void copyHeader(final HttpServletRequest request, final HttpHeaders headers, final String headerName) {
    String value = request.getHeader(headerName);
    if (value != null && !value.isBlank()) {
      headers.set(headerName, value);
    }
  }

  private static void addIfPresent(final MultiValueMap<String, Object> form, final String key, final String value) {
    if (value != null && !value.isBlank()) {
      form.add(key, value);
    }
  }

  private static IllegalStateException remoteFailure(
      final String operation,
      final RestClientResponseException exception
  ) {
    String detail = exception.getResponseBodyAsString();
    if (detail == null || detail.isBlank()) {
      detail = exception.getStatusText();
    }
    return new IllegalStateException(
        "统一对象存储" + operation + "失败: " + detail.trim(),
        exception
    );
  }

  private static String resolveFileName(final MultipartFile file, final ObjectStorageUploadRequest request) {
    if (request != null && request.getFileName() != null && !request.getFileName().isBlank()) {
      return request.getFileName().trim();
    }
    String originalFilename = file.getOriginalFilename();
    return originalFilename == null || originalFilename.isBlank() ? file.getName() : originalFilename;
  }
}
