package org.simplepoint.plugin.ai.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentSourceType;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentStatus;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

class KnowledgeDocumentQueriesTest {
  private static SessionFactory factory;
  private Session session;
  private AiKnowledgeDocumentServiceImpl service;
  private AiScopeAccessPolicy policy;

  @BeforeAll
  static void database() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:knowledge-doc-query;DB_CLOSE_DELAY=-1")
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(AiKnowledgeDocument.class)
            .buildMetadata()
            .buildSessionFactory();
  }

  @BeforeEach
  void data() {
    session = factory.openSession();
    session.beginTransaction();
    for (int index = 0; index < 32; index++) {
      var row = new AiKnowledgeDocument();
      row.setId("document-%02d".formatted(index));
      row.setName("Document %02d".formatted(index));
      row.setKnowledgeBaseId(index == 28 ? "other" : "kb");
      row.setTenantId(index == 29 ? "foreign" : "tenant");
      row.setScopeType(index == 30 ? AiResourceScope.SYSTEM : AiResourceScope.TENANT);
      row.setSourceType(AiKnowledgeDocumentSourceType.TEXT);
      row.setStatus(
          index % 2 == 0 ? AiKnowledgeDocumentStatus.READY : AiKnowledgeDocumentStatus.PENDING);
      row.setChunkCount(index / 2);
      row.setFileSize(index * 100L);
      if (index == 31) {
        row.setDeletedAt(Instant.now());
      }
      session.persist(row);
    }
    session.flush();
    session.clear();
    var base = new AiKnowledgeBase();
    base.setId("kb");
    base.setScopeType(AiResourceScope.TENANT);
    base.setTenantId("tenant");
    var bases = mock(AiKnowledgeBaseRepository.class);
    when(bases.findActiveById("kb")).thenReturn(Optional.of(base));
    var documents = mock(AiKnowledgeDocumentRepository.class);
    var repository =
        new BaseRepositoryImpl<AiKnowledgeDocument, String>(AiKnowledgeDocument.class, session);
    when(documents.limitScoped(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call ->
                repository.limitScoped(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    policy = mock(AiScopeAccessPolicy.class);
    service =
        new AiKnowledgeDocumentServiceImpl(
            bases, documents, null, null, policy, null, null, null, null, null);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    session.getTransaction().rollback();
    session.close();
  }

  @AfterAll
  static void close() {
    factory.close();
  }

  @Test
  void typedFiltersCountAndPaginationStayInsideOwnedActiveKnowledgeBase() {
    var result =
        service.limit(
            "kb",
            Map.of("status", "equals:READY", "chunkCount", "than:equal:greater:5"),
            PageRequest.of(1, 3, Sort.by(Sort.Direction.DESC, "fileSize")));
    assertThat(result.getTotalElements()).isEqualTo(9);
    assertThat(result.getContent())
        .extracting(AiKnowledgeDocument::getName)
        .containsExactly("Document 20", "Document 18", "Document 16");
    assertThat(service.limit("kb", Map.of(), PageRequest.of(0, 100)).getTotalElements())
        .isEqualTo(28);
    assertThat(
            service
                .limit("kb", Map.of("name", "Document 2"), PageRequest.of(0, 100))
                .getTotalElements())
        .isEqualTo(8);
  }

  @Test
  void clientCannotQueryAuthorizationFieldsOrExtractedDocumentContent() {
    for (String field :
        java.util.List.of(
            "tenantId",
            "knowledgeBaseId",
            "scopeType",
            "deletedAt",
            "extractedText",
            "metadataJson")) {
      assertThatThrownBy(
              () -> service.limit("kb", Map.of(field, "equals:foreign"), PageRequest.of(0, 20)))
          .as("public query field %s", field)
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> service.limit("kb", Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
    var schema =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(service.querySchema("kb"));
    assertThat(schema.at("/schema/properties/status/oneOf").size()).isEqualTo(6);
    assertThat(schema.at("/schema/properties/fileSize/type").asText()).isEqualTo("integer");
    assertThat(schema.at("/schema/properties/extractedText").isMissingNode()).isTrue();
  }

  @Test
  void fieldPermissionAndOwnerDenialApplyToSchemaAndData() {
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("AiKnowledgeDocument#fileSize", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(
            () -> service.limit("kb", Map.of(), PageRequest.of(0, 20, Sort.by("fileSize"))))
        .isInstanceOf(InvalidQueryException.class);
    var schema =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(service.querySchema("kb"));
    assertThat(schema.at("/schema/properties/fileSize").isMissingNode()).isTrue();
    org.mockito.Mockito.doThrow(new AccessDeniedException("other workspace"))
        .when(policy)
        .assertCanManageOwnedResource(AiResourceScope.TENANT, "tenant");
    assertThatThrownBy(() -> service.querySchema("kb")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.limit("kb", Map.of(), PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);
  }
}
