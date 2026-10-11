package org.simplepoint.plugin.ai.mcp.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
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
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class McpSnapshotQueriesTest {
  private static SessionFactory factory;
  private Session session;
  private McpSnapshotQueries queries;
  private AiMcpServerDefinition server;

  @BeforeAll
  static void database() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:mcp-snapshot-query;DB_CLOSE_DELAY=-1")
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(AiMcpCapabilitySnapshot.class)
            .buildMetadata()
            .buildSessionFactory();
  }

  @BeforeEach
  void data() {
    session = factory.openSession();
    session.beginTransaction();
    server = new AiMcpServerDefinition();
    server.setId("server");
    server.setScopeType(AiResourceScope.TENANT);
    server.setTenantId("tenant");
    server.setActiveSnapshotId("snapshot-20");
    for (int index = 0; index < 28; index++) {
      var snapshot = new AiMcpCapabilitySnapshot();
      snapshot.setId("snapshot-%02d".formatted(index));
      snapshot.setServerId(index == 24 ? "foreign" : "server");
      snapshot.setTenantId(index == 25 ? "foreign" : "tenant");
      snapshot.setScopeType(index == 26 ? AiResourceScope.SYSTEM : AiResourceScope.TENANT);
      if (index == 27) {
        snapshot.setDeletedAt(Instant.now());
      }
      snapshot.setRemoteServerName("Remote %02d".formatted(index));
      snapshot.setRemoteServerVersion(index % 2 == 0 ? "1" : "2");
      snapshot.setProtocolVersion("2025-03-26");
      snapshot.setSchemaHash("hash-%02d".formatted(index));
      snapshot.setDiscoveredAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index / 2));
      snapshot.setCapabilitiesJson("{}");
      snapshot.setToolsJson("[]");
      session.persist(snapshot);
    }
    session.flush();
    session.clear();
    queries = new McpSnapshotQueries(session);
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
  void filtersAndSortPrecedePaginationAndCountsEnforceServerScopeAndSoftDeletion() {
    var result =
        queries.page(
            server,
            Map.of("remoteServerName", "like:REMOTE", "remoteServerVersion", "equals:2"),
            PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "remoteServerName")));
    assertThat(result.getTotalElements()).isEqualTo(12);
    assertThat(result.getContent())
        .extracting(AiMcpCapabilitySnapshot::getId)
        .containsExactly("snapshot-13", "snapshot-11", "snapshot-09", "snapshot-07", "snapshot-05");
    assertThat(queries.page(server, Map.of(), PageRequest.of(0, 100)).getTotalElements())
        .isEqualTo(24);
  }

  @Test
  void currentSnapshotIsAQueryableBooleanNotAClientSidePageDecoration() {
    assertThat(
            queries
                .page(server, Map.of("active", "equals:true"), PageRequest.of(0, 20))
                .getContent())
        .extracting(AiMcpCapabilitySnapshot::getId)
        .containsExactly("snapshot-20");
    assertThat(
            queries
                .page(
                    server, Map.of(), PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "active")))
                .getContent())
        .extracting(AiMcpCapabilitySnapshot::getId)
        .containsExactly("snapshot-20");
    server.setActiveSnapshotId(null);
    assertThat(
            queries
                .page(server, Map.of("active", "equals:true"), PageRequest.of(0, 20))
                .getTotalElements())
        .isZero();
    assertThat(
            queries
                .page(server, Map.of("active", "equals:false"), PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(24);
  }

  @Test
  void predecessorUsesCanonicalDiscoveryOrderWithDeterministicTimestampTies() {
    assertThat(queries.previous(server, "snapshot-20"))
        .get()
        .extracting(AiMcpCapabilitySnapshot::getId)
        .isEqualTo("snapshot-21");
    assertThat(queries.previous(server, "snapshot-21"))
        .get()
        .extracting(AiMcpCapabilitySnapshot::getId)
        .isEqualTo("snapshot-18");
    assertThat(queries.previous(server, "snapshot-01")).isEmpty();
    assertThatThrownBy(() -> queries.previous(server, "snapshot-25"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queries.previous(server, "absent"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownJsonFieldsPagingOverflowAndMaskedFieldsFailClosed() {
    for (String field :
        java.util.List.of("tenantId", "serverId", "toolsJson", "capabilitiesJson", "toolCount")) {
      assertThatThrownBy(
              () -> queries.page(server, Map.of(field, "is:null"), PageRequest.of(0, 20)))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> queries.page(server, Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> queries.page(server, Map.of(), Pageable.unpaged()))
        .isInstanceOf(InvalidQueryException.class);
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("McpCapabilitySnapshotSummary#schemaHash", "MASKED"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(
            () -> queries.page(server, Map.of(), PageRequest.of(0, 20, Sort.by("schemaHash"))))
        .isInstanceOf(InvalidQueryException.class);
  }
}
