package org.simplepoint.plugin.ai.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderVendor;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class AiEmbeddingModelOptionsDatabaseTest {
  SessionFactory factory;
  Session session;
  List<String> statements = new ArrayList<>();
  ScopeAssignment tenant = new ScopeAssignment(AiResourceScope.TENANT, "t1");
  PageRequest first = PageRequest.of(0, 20, Sort.by("name"));
  static final String PAGE = "ai.workbench.knowledge-bases";

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:embedding-options-" + java.util.UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {statements.add(sql); return sql;})
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(AiModelDefinition.class).addAnnotatedClass(AiProviderDefinition.class).buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    for (int index = 0; index < 521; index++) model("Shared%03d".formatted(index), AiResourceScope.SYSTEM, null);
    for (int index = 0; index < 521; index++) model("Owned%03d".formatted(index), AiResourceScope.TENANT, "t1");
    for (int index = 0; index < 521; index++) model("Foreign%03d".formatted(index), AiResourceScope.TENANT, "t2");
    model("Legacy", null, null);
    model("Unavailable", AiResourceScope.SYSTEM, null).setAvailable(false);
    model("Disabled", AiResourceScope.SYSTEM, null).setEnabled(false);
    model("Deleted", AiResourceScope.SYSTEM, null).setDeletedAt(Instant.now());
    model("WrongType", AiResourceScope.SYSTEM, null).setModelType(AiModelType.LLM);
    model("Literal%_!", AiResourceScope.TENANT, "t1");
    model("EmptyLabel", AiResourceScope.TENANT, "t1").setDisplayName("   ");
    session.flush(); session.clear(); factory.getStatistics().clear(); statements.clear();
  }

  AiModelDefinition model(String name, AiResourceScope scope, String tenantId) {
    var model = new AiModelDefinition();
    model.setProviderId("provider"); model.setModelId(name); model.setDisplayName(name);
    model.setScopeType(scope); model.setTenantId(tenantId); model.setModelType(AiModelType.EMBEDDING);
    model.setEnabled(true); model.setAvailable(true); model.setBillingEnabled(false); model.setMetadataJson("sensitive-metadata-" + "x".repeat(8192));
    session.persist(model); return model;
  }

  @AfterEach
  void cleanup() {
    ResourcePolicyContext.clear(); RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  @Test
  void countsAndPagesSharedPlusOwnedEmbeddingsWithoutEntitiesOrMetadata() {
    var page = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of(), PageRequest.of(26, 20, Sort.by("name")));
    assertThat(page.getTotalElements()).isEqualTo(1045);
    assertThat(page.getContent()).hasSize(20).noneMatch(row -> row.name().startsWith("Foreign"));
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements).hasSize(2);
    assertThat(statements.get(1)).contains("offset", "fetch first")
        .doesNotContain("metadata_json", "token_price", "credential", "description");
    var last = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of(), PageRequest.of(52, 20, Sort.by("name")));
    assertThat(last.getContent()).extracting(row -> row.name()).containsExactly("Shared516", "Shared517", "Shared518", "Shared519", "Shared520");
    var platform = AiEmbeddingModelOptionsQuery.page(session, new ScopeAssignment(AiResourceScope.SYSTEM, null), Map.of(), first);
    assertThat(platform.getTotalElements()).isEqualTo(522);
    assertThat(platform.getContent()).noneMatch(row -> row.name().startsWith("Owned") || row.name().startsWith("Foreign"));
  }

  @Test
  void literalSearchAndHydrationUseTheSameMandatoryScopeAndBoundedQueryContract() {
    var literal = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", """
        {"version":1,"where":{"field":"name","op":"like","value":"%_!"}}
        """), first);
    assertThat(literal.getContent()).extracting(row -> row.name()).containsExactly("Literal%_!");
    var selected = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "equals:Owned520"), first).getContent().getFirst();
    var lookup = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("id", "equals:" + selected.id()), PageRequest.of(0, 1));
    assertThat(lookup.getContent()).containsExactly(selected);
    var foreign = AiEmbeddingModelOptionsQuery.page(session, new ScopeAssignment(AiResourceScope.TENANT, "t2"), Map.of("name", "equals:Foreign520"), first).getContent().getFirst();
    assertThat(AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("id", "equals:" + foreign.id()), PageRequest.of(0, 1)).getTotalElements()).isZero();
    assertThatThrownBy(() -> AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of(), PageRequest.of(0, 101))).isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("tenantId", "equals:t2"), first)).isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of(), PageRequest.of(0, 20, Sort.by("metadataJson")))).isInstanceOf(InvalidQueryException.class);
    assertThat(AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "equals:EmptyLabel"), first).getContent()).hasSize(1);
  }

  @Test
  void activePageDataAndDerivedFieldPoliciesAlsoConstrainCountsSearchAndLookup() {
    var context = new AuthorizationContext(); context.setUserId("current");
    context.setResourceDataScopes(Map.of(PAGE, new ResourceDataScope("NONE", Set.of(), false,
        List.of("modelId = \"Owned520\""))));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context); ResourcePolicyContext.set(PAGE);
    var page = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of(), first);
    assertThat(page.getTotalElements()).isEqualTo(1); assertThat(page.getContent().getFirst().name()).isEqualTo("Owned520");
    assertThat(AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "equals:Shared520"), first).getTotalElements()).isZero();
    var hidden = new AuthorizationContext();
    hidden.setResourceFieldPermissions(Map.of(PAGE, Map.of(AiModelDefinition.class.getName() + "#displayName", "HIDDEN")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, hidden);
    assertThatThrownBy(() -> AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "like:Owned"), first)).isInstanceOf(InvalidQueryException.class);
    assertThat(AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of(), PageRequest.of(0, 1)).getContent().getFirst().name()).isEqualTo("***");
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
  }

  @Test
  void providerDiscriminatorsRemainScalarAndCannotRevealHiddenProviderNamesOrCredentials() {
    for (String label : List.of("Provider A", "Provider B")) {
      var provider = new AiProviderDefinition(); provider.setScopeType(AiResourceScope.SYSTEM);
      provider.setName(label); provider.setCode(label); provider.setProviderType(AiProviderType.OPENAI_COMPATIBLE);
      provider.setVendor(AiProviderVendor.CUSTOM); provider.setBaseUrl("https://fixture.test");
      provider.setCredentialCiphertext("private-provider-credential"); session.persist(provider);
      var model = model("duplicate-" + label, AiResourceScope.SYSTEM, null);
      model.setDisplayName("Same Model"); model.setProviderId(provider.getId());
    }
    session.flush(); session.clear(); factory.getStatistics().clear(); statements.clear();
    var visible = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "like:Same Model"), first);
    assertThat(visible.getContent()).extracting(row -> row.name()).containsExactlyInAnyOrder("Same Model · Provider A", "Same Model · Provider B");
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements.get(1)).contains("left join").doesNotContain("credential_ciphertext", "metadata_json", "base_url");
    var context = new AuthorizationContext(); context.setFieldPermissions(Map.of(AiProviderDefinition.class.getName() + "#name", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var redacted = AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "like:Same Model"), first);
    assertThat(redacted.getContent()).extracting(row -> row.name()).containsExactly("Same Model", "Same Model");
    assertThat(AiEmbeddingModelOptionsQuery.page(session, tenant, Map.of("name", "like:Provider A"), first).getTotalElements()).isZero();
  }
}
