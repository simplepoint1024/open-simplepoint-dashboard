/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.base.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.http.Response;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

class BaseControllerTest {

  private static class StubEntity implements BaseEntity<String> {
    private String id;
    private String createOrgDeptId;

    @Override
    public String getId() {
      return id;
    }

    @Override
    public void setId(String s) {
      this.id = s;
    }

    @Override
    public java.time.Instant getCreatedAt() {
      return null;
    }

    @Override
    public void setCreatedAt(java.time.Instant t) {
    }

    @Override
    public java.time.Instant getUpdatedAt() {
      return null;
    }

    @Override
    public void setUpdatedAt(java.time.Instant t) {
    }

    @Override
    public java.time.Instant getDeletedAt() {
      return null;
    }

    @Override
    public void setDeletedAt(java.time.Instant t) {
    }

    @Override
    public String getCreatedBy() {
      return null;
    }

    @Override
    public void setCreatedBy(String s) {
    }

    @Override
    public String getUpdatedBy() {
      return null;
    }

    @Override
    public void setUpdatedBy(String s) {
    }

    @Override
    public String getCreateOrgDeptId() {
      return createOrgDeptId;
    }

    @Override
    public void setCreateOrgDeptId(String s) {
      this.createOrgDeptId = s;
    }
  }

  @SuppressWarnings("unchecked")
  private final BaseService<StubEntity, String> mockService = mock(BaseService.class);

  private final BaseController<BaseService<StubEntity, String>, StubEntity, String> controller =
      new BaseController<>(mockService);

  @Test
  void schema_delegatesToServiceAndWrapsResult() {
    Map<String, Object> schema = Map.of("key", "value");
    when(mockService.schema()).thenReturn(schema);

    Response<Map<String, Object>> response = controller.schema();

    assertThat(response).isNotNull();
    assertThat(response.getBody()).isEqualTo(schema);
  }

  @Test
  void staticIse_returnsIseResponse() {
    Response<String> response = BaseController.ise();
    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(500);
  }

  @Test
  void staticBr_returnsBadRequestResponse() {
    Response<String> response = BaseController.br("body", String.class);
    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void staticNf_returnsNotFoundResponse() {
    Response<String> response = BaseController.nf("body", String.class);
    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void staticOk_wrapsBody() {
    Response<String> response = BaseController.ok("hello");
    assertThat(response).isNotNull();
    assertThat(response.getBody()).isEqualTo("hello");
  }

  private static class TestController extends
      BaseController<BaseService<StubEntity, String>, StubEntity, String> {
    TestController(BaseService<StubEntity, String> service) {
      super(service);
    }

    Response<StubEntity> callOk() {
      return ok();
    }

    Response<Page<StubEntity>> callLimit(Page<StubEntity> page) {
      return limit(page, StubEntity.class);
    }
  }

  @SuppressWarnings("unchecked")
  private final TestController testController = new TestController(
      (BaseService<StubEntity, String>) mock(BaseService.class));

  @Test
  void ok_noArgs_returnsSuccessResponse() {
    Response<StubEntity> response = testController.callOk();
    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void limit_wrapsPage() {
    Page<StubEntity> page = new PageImpl<>(java.util.List.of());
    Response<Page<StubEntity>> response = testController.callLimit(page);
    assertThat(response).isNotNull();
  }
}
