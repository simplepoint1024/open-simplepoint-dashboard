/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.client.model.ObjectStorageRemoteContent;
import org.simplepoint.plugin.storage.client.properties.ObjectStorageRemoteProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RestClientObjectStorageRemoteServiceTest {

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void upload_forwardsTenantAndAuthenticationContext() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestClientObjectStorageRemoteService service = service(builder);
    final MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
    servletRequest.addHeader("X-Tenant-Id", "tenant-1");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
    server.expect(requestTo("http://common/object-storage/upload"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
        .andExpect(header("X-Tenant-Id", "tenant-1"))
        .andRespond(withSuccess(
            "{\"id\":\"object-1\",\"originalFileName\":\"测试文档.md\"}",
            MediaType.APPLICATION_JSON
        ));
    final ObjectStorageUploadRequest request = new ObjectStorageUploadRequest();
    request.setDirectory("ai/knowledge-bases/kb-1");
    request.setSourceServiceName("ai");

    final ObjectStorageObject result = service.upload(
        new MockMultipartFile(
            "file",
            "测试文档.md",
            "text/markdown",
            "content".getBytes(StandardCharsets.UTF_8)
        ),
        request
    );

    assertThat(result.getId()).isEqualTo("object-1");
    assertThat(result.getOriginalFileName()).isEqualTo("测试文档.md");
    server.verify();
  }

  @Test
  void download_returnsContentAndResponseMetadata() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestClientObjectStorageRemoteService service = service(builder);
    server.expect(requestTo("http://common/object-storage/objects/object-1/content"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("hello", MediaType.TEXT_PLAIN)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.txt"
            ));

    final ObjectStorageRemoteContent result = service.download("object-1");

    assertThat(result.content()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(result.fileName()).isEqualTo("测试.txt");
    assertThat(result.contentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
    server.verify();
  }

  @Test
  void delete_callsUnifiedObjectEndpoint() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestClientObjectStorageRemoteService service = service(builder);
    server.expect(requestTo("http://common/object-storage/objects/object-1"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess("object-1", MediaType.APPLICATION_JSON));

    service.delete("object-1");

    server.verify();
  }

  @Test
  void delete_missingObject_isIdempotent() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestClientObjectStorageRemoteService service = service(builder);
    server.expect(requestTo("http://common/object-storage/objects/missing"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    service.delete("missing");

    server.verify();
  }

  @Test
  void upload_remoteBadRequest_exposesUtf8BusinessMessage() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestClientObjectStorageRemoteService service = service(builder);
    server.expect(requestTo("http://common/object-storage/upload"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .body("上传文件不能为空"));

    assertThatThrownBy(() -> service.upload(
        new MockMultipartFile(
            "file",
            "空文件.txt",
            MediaType.TEXT_PLAIN_VALUE,
            new byte[0]
        ),
        new ObjectStorageUploadRequest()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("统一对象存储上传失败")
        .hasMessageContaining("上传文件不能为空");
    server.verify();
  }

  private static RestClientObjectStorageRemoteService service(final RestClient.Builder builder) {
    final ObjectStorageRemoteProperties properties = new ObjectStorageRemoteProperties();
    properties.setServiceName("common");
    return new RestClientObjectStorageRemoteService(builder, properties);
  }
}
