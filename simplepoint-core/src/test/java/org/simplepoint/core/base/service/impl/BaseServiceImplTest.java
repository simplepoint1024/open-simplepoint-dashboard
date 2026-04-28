/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.base.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.core.bean.copier.CopyOptions;
import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.api.base.TenantBaseEntity;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@SuppressWarnings({"unchecked", "rawtypes"})
class BaseServiceImplTest {

  // ---- Stub entity --------------------------------------------------------

  static class StubEntity implements BaseEntity<String> {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private String createdBy;
    private String updatedBy;
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
    public Instant getCreatedAt() {
      return createdAt;
    }

    @Override
    public void setCreatedAt(Instant t) {
      this.createdAt = t;
    }

    @Override
    public Instant getUpdatedAt() {
      return updatedAt;
    }

    @Override
    public void setUpdatedAt(Instant t) {
      this.updatedAt = t;
    }

    @Override
    public Instant getDeletedAt() {
      return deletedAt;
    }

    @Override
    public void setDeletedAt(Instant t) {
      this.deletedAt = t;
    }

    @Override
    public String getCreatedBy() {
      return createdBy;
    }

    @Override
    public void setCreatedBy(String s) {
      this.createdBy = s;
    }

    @Override
    public String getUpdatedBy() {
      return updatedBy;
    }

    @Override
    public void setUpdatedBy(String s) {
      this.updatedBy = s;
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

  // ---- Stub tenant entity -------------------------------------------------

  static class StubTenantEntity extends StubEntity implements TenantBaseEntity<String> {
    private String tenantId;

    @Override
    public String getTenantId() {
      return tenantId;
    }

    @Override
    public void setTenantId(String tenantId) {
      this.tenantId = tenantId;
    }
  }

  // ---- Concrete service under test ----------------------------------------

  static class TestServiceImpl extends BaseServiceImpl<
      BaseRepository<StubEntity, String>,
      StubEntity,
      String> {

    AuthorizationContext injectedContext;

    TestServiceImpl(BaseRepository<StubEntity, String> repo, DetailsProviderService dps) {
      super(repo, dps);
    }

    @Override
    public AuthorizationContext getAuthorizationContext() {
      return injectedContext;
    }
  }

  // ---- Fields -------------------------------------------------------------

  private BaseRepository<StubEntity, String> repository;
  private DetailsProviderService detailsProviderService;
  private TestServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(BaseRepository.class);
    detailsProviderService = mock(DetailsProviderService.class);
    service = new TestServiceImpl(repository, detailsProviderService);
  }

  // ---- Tests: delegation to repository -----------------------------------

  @Test
  void findById_delegatesToRepository() {
    StubEntity entity = new StubEntity();
    when(repository.findById("id-1")).thenReturn(Optional.of(entity));

    Optional<StubEntity> result = service.findById("id-1");

    assertThat(result).contains(entity);
  }

  @Test
  void findAllByIds_delegatesToRepository() {
    StubEntity e1 = new StubEntity();
    when(repository.findAllByIds(any(Iterable.class))).thenReturn(List.of(e1));

    List<StubEntity> result = service.findAllByIds(List.of("a", "b"));

    assertThat(result).containsExactly(e1);
  }

  @Test
  void findAll_delegatesToRepository() {
    StubEntity e = new StubEntity();
    when(repository.findAll(any(Map.class))).thenReturn(List.of(e));

    List<StubEntity> result = service.findAll(Map.of("k", "v"));

    assertThat(result).containsExactly(e);
  }

  @Test
  void existsById_delegatesToRepository() {
    when(repository.existsById("x")).thenReturn(true);

    assertThat(service.existsById("x")).isTrue();
  }

  @Test
  void exists_delegatesToRepository() {
    StubEntity e = new StubEntity();
    when(repository.exists(e)).thenReturn(false);

    assertThat(service.exists(e)).isFalse();
  }

  @Test
  void count_delegatesToRepository() {
    StubEntity e = new StubEntity();
    when(repository.count(e)).thenReturn(5L);

    assertThat(service.count(e)).isEqualTo(5L);
  }

  @Test
  void flush_delegatesToRepository() {
    service.flush();
    verify(repository).flush();
  }

  @Test
  void removeAll_delegatesToRepository() {
    service.removeAll();
    verify(repository).deleteAll();
  }

  @Test
  void removeById_delegatesToRepository() {
    when(repository.findById("id-1")).thenReturn(Optional.empty());

    service.removeById("id-1");

    verify(repository).deleteById("id-1");
  }

  @Test
  void removeByIds_whenNoMatchingEntities_callsDeleteByIds() {
    when(repository.findAllByIds(any())).thenReturn(List.of());

    service.removeByIds(List.of("a", "b"));

    verify(repository).deleteByIds(any());
  }

  // ---- Tests: create -----------------------------------------------------

  @Test
  void create_single_savesAndReturns() {
    StubEntity entity = new StubEntity();
    entity.setId("new-1");
    when(repository.save(entity)).thenReturn(entity);
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(Set.of());

    StubEntity result = service.create(entity);

    assertThat(result).isSameAs(entity);
    verify(repository).save(entity);
  }

  @Test
  void create_collection_savesAndReturns() {
    StubEntity e1 = new StubEntity();
    when(repository.saveAll(any())).thenReturn(List.of(e1));
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(Set.of());

    List<StubEntity> result = service.create(List.of(e1));

    assertThat(result).containsExactly(e1);
  }

  // ---- Tests: tenant ID injection ----------------------------------------

  @Test
  void applyCurrentTenantId_setsIdOnTenantEntity_whenContextPresent() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant-abc"));
    service.injectedContext = ctx;

    StubTenantEntity entity = new StubTenantEntity();
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(Set.of());

    service.create(entity);

    assertThat(entity.getTenantId()).isEqualTo("tenant-abc");
  }

  @Test
  void applyCurrentTenantId_doesNotOverwrite_whenTenantIdAlreadySet() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant-abc"));
    service.injectedContext = ctx;

    StubTenantEntity entity = new StubTenantEntity();
    entity.setTenantId("existing-tenant");
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(Set.of());

    service.create(entity);

    assertThat(entity.getTenantId()).isEqualTo("existing-tenant");
  }

  @Test
  void applyCurrentTenantId_skipsNonTenantEntity() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant-abc"));
    service.injectedContext = ctx;

    StubEntity entity = new StubEntity();
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class)).thenReturn(Set.of());

    service.create(entity);

    assertThat(entity.getId()).isNull();
  }

  @Test
  void currentTenantId_returnsNull_whenContextIsNull() {
    service.injectedContext = null;
    assertThat(service.currentTenantId()).isNull();
  }

  @Test
  void currentTenantId_returnsNull_whenAttributeBlank() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setAttributes(Map.of("X-Tenant-Id", "  "));
    service.injectedContext = ctx;

    assertThat(service.currentTenantId()).isNull();
  }

  @Test
  void currentTenantId_returnsNull_whenAttributeAbsent() {
    AuthorizationContext ctx = new AuthorizationContext();
    service.injectedContext = ctx;

    assertThat(service.currentTenantId()).isNull();
  }

  // ---- Tests: getModifyDataAuditingServices ------------------------------

  @Test
  void getModifyDataAuditingServices_returnsDialects() {
    ModifyDataAuditingService auditor = mock(ModifyDataAuditingService.class);
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class))
        .thenReturn(Set.of(auditor));

    Collection<ModifyDataAuditingService> services = service.getModifyDataAuditingServices();

    assertThat(services).containsExactly(auditor);
  }

  @Test
  void getModifyDataAuditingServices_returnsEmptySet_whenExceptionThrown() {
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class))
        .thenThrow(new RuntimeException("No beans"));

    Collection<ModifyDataAuditingService> services = service.getModifyDataAuditingServices();

    assertThat(services).isEmpty();
  }

  // ---- Tests: limit ------------------------------------------------------

  @Test
  void limit_delegatesToRepositoryAndCallsValidate() {
    Page<StubEntity> page = new PageImpl<>(List.of());
    JsonSchemaDetailsService jss = mock(JsonSchemaDetailsService.class);
    when(detailsProviderService.getDialect(JsonSchemaDetailsService.class)).thenReturn(jss);
    when(repository.limit(any(), any())).thenReturn((Page) page);

    Page<StubEntity> result = service.limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result).isSameAs(page);
  }

  // ---- Tests: getCopyOptions ---------------------------------------------

  @Test
  void getCopyOptions_returnsNonNull() {
    CopyOptions opts = service.getCopyOptions();
    assertThat(opts).isNotNull();
  }

  // ---- Tests: modifyById -------------------------------------------------

  @Test
  void modifyById_throwsNpe_whenEntityNull() {
    assertThatThrownBy(() -> service.modifyById(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void modifyById_throwsNpe_whenEntityIdNull() {
    StubEntity entity = new StubEntity();

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void modifyById_whenEntityNotFound_delegatesToUpdateById() {
    StubEntity entity = new StubEntity();
    entity.setId("id-99");
    when(repository.findById("id-99")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    StubEntity result = service.modifyById(entity);

    assertThat(result).isSameAs(entity);
    verify(repository).updateById(entity);
  }

  // ---- Tests: schema + getJsonSchema + getButtonDeclarationsSchema --------

  @ButtonDeclarations({
      @org.simplepoint.core.annotation.ButtonDeclaration(
          key = "add", authority = "test:add",
          type = org.simplepoint.core.enums.ButtonType.PRIMARY
      )
  })
  static class AnnotatedEntity extends StubEntity {
  }

  @Test
  void schema_returnsSchemaAndEmptyButtons_forEntityWithoutButtonDeclarations() {
    setupSchemaGeneratorMocks(StubEntity.class, new com.fasterxml.jackson.databind.ObjectMapper());
    service.injectedContext = new AuthorizationContext();

    Map<String, Object> result = service.schema();

    assertThat(result).containsKeys("schema", "buttons");
    assertThat((java.util.Set<?>) result.get("buttons")).isEmpty();
  }

  @Test
  void schema_returnsButtons_whenAdministratorAndClassHasButtonDeclarations() {
    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    setupSchemaGeneratorMocks(AnnotatedEntity.class, om);
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(true);
    ctx.setPermissions(java.util.Collections.emptySet());
    service.injectedContext = ctx;

    Map<String, Object> result = service.schema();

    assertThat((java.util.Set<?>) result.get("buttons")).isNotEmpty();
  }

  @Test
  void getButtonDeclarationsSchema_returnsButton_whenPermissionMatches() {
    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    setupSchemaGeneratorMocks(AnnotatedEntity.class, om);
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(false);
    ctx.setPermissions(Set.of("test:add"));
    service.injectedContext = ctx;

    Set<Map<String, Object>> result = service.getButtonDeclarationsSchema(
        (Class<StubEntity>) (Class<?>) AnnotatedEntity.class);

    assertThat(result).isNotEmpty();
    assertThat(result.iterator().next()).containsEntry("key", "add");
  }

  @Test
  void getButtonDeclarationsSchema_returnsEmpty_whenNoPermissionAndNotAdmin() {
    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    setupSchemaGeneratorMocks(AnnotatedEntity.class, om);
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(false);
    ctx.setPermissions(java.util.Collections.emptySet());
    service.injectedContext = ctx;

    Set<Map<String, Object>> result = service.getButtonDeclarationsSchema(
        (Class<StubEntity>) (Class<?>) AnnotatedEntity.class);

    assertThat(result).isEmpty();
  }

  @Test
  void extractAnnotationAttributes_returnsAnnotationValues() {
    org.simplepoint.core.annotation.ButtonDeclaration decl = AnnotatedEntity.class
        .getAnnotation(org.simplepoint.core.annotation.ButtonDeclarations.class).value()[0];

    Map<String, Object> attrs = service.extractAnnotationAttributes(decl);

    assertThat(attrs).containsEntry("key", "add");
    assertThat(attrs).containsEntry("authority", "test:add");
  }

  @Test
  void getAllFieldNames_returnsFieldsFromSchema() {
    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    setupSchemaGeneratorMocks(StubEntity.class, om);
    service.injectedContext = new AuthorizationContext();

    Set<String> names = service.getAllFieldNames(StubEntity.class);

    assertThat(names).contains("name");
  }

  @Test
  void getJsonSchema_throwsWhenDetailsProviderServiceIsNull() {
    TestServiceImpl svc = new TestServiceImpl(repository, null);
    assertThatThrownBy(() -> svc.getJsonSchema(StubEntity.class))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void getJsonSchema_throwsWhenJsonSchemaDetailsServiceIsNull() {
    when(detailsProviderService.getDialect(JsonSchemaDetailsService.class)).thenReturn(null);
    assertThatThrownBy(() -> service.getJsonSchema(StubEntity.class))
        .isInstanceOf(RuntimeException.class);
  }

  private void setupSchemaGeneratorMocks(Class<? extends StubEntity> domainClass,
      com.fasterxml.jackson.databind.ObjectMapper om) {
    var jss = mock(JsonSchemaDetailsService.class);
    var jsg = mock(org.simplepoint.api.security.generator.JsonSchemaGenerator.class);
    when(detailsProviderService.getDialect(JsonSchemaDetailsService.class)).thenReturn(jss);
    when(detailsProviderService.getDialect(org.simplepoint.api.security.generator.JsonSchemaGenerator.class))
        .thenReturn(jsg);
    when(repository.getDomainClass()).thenReturn((Class) domainClass);
    com.fasterxml.jackson.databind.node.ObjectNode schemaNode = om.createObjectNode();
    com.fasterxml.jackson.databind.node.ObjectNode propsNode = om.createObjectNode();
    // Two properties: one with x-order (covers null != null branch), one without (covers null branch)
    propsNode.set("name", om.createObjectNode().put("x-order", 1));
    propsNode.set("code", om.createObjectNode());
    schemaNode.set("properties", propsNode);
    when(jsg.generateSchema(domainClass)).thenReturn(schemaNode);
  }

  @Test
  void modifyById_whenEntityFound_copiesRestrictedFields() {
    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    setupSchemaGeneratorMocks(StubEntity.class, om);
    service.injectedContext = new AuthorizationContext();

    StubEntity existing = new StubEntity();
    existing.setId("id-1");
    existing.setCreatedBy("original-author");

    StubEntity incoming = new StubEntity();
    incoming.setId("id-1");

    when(repository.findById("id-1")).thenReturn(Optional.of(existing));
    when(repository.updateById(incoming)).thenReturn(incoming);
    when(detailsProviderService.getDialects(
        org.simplepoint.api.base.audit.ModifyDataAuditingService.class)).thenReturn(Set.of());

    StubEntity result = service.modifyById(incoming);

    assertThat(result).isSameAs(incoming);
    verify(repository).updateById(incoming);
  }

  @Test
  void removeByIds_whenDataNotEmpty_callsAuditService() {
    StubEntity entity = new StubEntity();
    entity.setId("del-1");
    when(repository.findAllByIds(any())).thenReturn(List.of(entity));
    org.simplepoint.api.base.audit.ModifyDataAuditingService auditor =
        mock(org.simplepoint.api.base.audit.ModifyDataAuditingService.class);
    when(detailsProviderService.getDialects(
        org.simplepoint.api.base.audit.ModifyDataAuditingService.class))
        .thenReturn(Set.of(auditor));

    service.removeByIds(List.of("del-1"));

    verify(repository).deleteByIds(any());
    verify(auditor).delete(any(), any());
  }

  @Test
  void getAuthorizationContext_defaultImpl_returnsFromContextHolder() {
    // Use the real base implementation (no override)
    BaseServiceImpl<BaseRepository<StubEntity, String>, StubEntity, String> realService =
        new BaseServiceImpl<>(repository, detailsProviderService) {};
    // No request context → should return null (not throw)
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    AuthorizationContext ctx = realService.getAuthorizationContext();
    assertThat(ctx).isNull();
  }
}
