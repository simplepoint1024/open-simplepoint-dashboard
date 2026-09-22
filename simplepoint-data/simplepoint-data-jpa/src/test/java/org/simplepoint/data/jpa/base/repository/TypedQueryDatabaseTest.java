package org.simplepoint.data.jpa.base.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.query.InvalidQueryException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Real SQL through the same repository, service policy and exception translation as production. */
class TypedQueryDatabaseTest {
  enum State {
    READY,
    DONE
  }

  @Entity(name = "TypedQueryRow")
  @Table(
      name = "typed_query_row",
      indexes =
          @jakarta.persistence.Index(
              name = "idx_typed_query_amount",
              columnList = "tenant_id,deleted_at,amount,id"))
  @org.simplepoint.core.query.TableQuery(
      value = {
        @org.simplepoint.core.query.QueryField("title"),
            @org.simplepoint.core.query.QueryField("amount"),
        @org.simplepoint.core.query.QueryField("enabled"),
            @org.simplepoint.core.query.QueryField("dueAt"),
        @org.simplepoint.core.query.QueryField("state"),
        @org.simplepoint.core.query.QueryField("sort")
      },
      relations =
          @org.simplepoint.core.query.QueryRelation(
              value = "labels",
              target = Label.class,
              foreignKey = "rowId"))
  public static class Row extends TenantBaseEntityImpl<String> {
    String title;
    Integer amount;
    Integer sort;
    Boolean enabled;
    Instant dueAt;

    @Enumerated(EnumType.STRING)
    State state;
  }

  @Entity(name = "QueryLabel")
  @Table(name = "query_label")
  @org.simplepoint.core.query.TableQuery(@org.simplepoint.core.query.QueryField("label"))
  public static class Label extends TenantBaseEntityImpl<String> {
    String rowId;
    String label;
  }

  private static SessionFactory factory;
  private Session session;
  private BaseRepositoryImpl<Row, String> repository;
  private BaseServiceImpl<BaseRepositoryImpl<Row, String>, Row, String> service;

  @BeforeAll
  static void database() {
    String url = System.getenv("QUERY_TEST_DB_URL");
    if (url != null && url.isBlank()) url = null;
    if (url != null && !url.equals("jdbc:postgresql://127.0.0.1:45434/query_test")) {
      throw new IllegalArgumentException(
          "Only the disposable localhost query_test database is allowed");
    }
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url",
                url == null ? "jdbc:h2:mem:typed-query;DB_CLOSE_DELAY=-1" : url)
            .applySetting("hibernate.connection.username", url == null ? "sa" : "query_test")
            .applySetting("hibernate.connection.password", url == null ? "" : "query_test")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(Row.class)
            .addAnnotatedClass(Label.class)
            .buildMetadata()
            .buildSessionFactory();
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    session = factory.openSession();
    session.beginTransaction();
    row(
        "prefix Alpha %_\\:tail suffix",
        2, true, "2026-09-18T10:20:30Z", State.READY, "tenant-a", "user-a", false);
    row("Beta", 10, false, "2026-09-19T10:20:30Z", State.DONE, "tenant-a", "user-b", false);
    row("Gamma", 100, true, null, State.READY, "tenant-a", "user-a", false);
    row("Deleted", 10, false, null, State.DONE, "tenant-a", "user-a", true);
    row("Foreign", 10, true, null, State.READY, "tenant-b", "user-a", false);
    session.flush();
    session.clear();
    var proxy = new ProxyFactory(new BaseRepositoryImpl<>(Row.class, session));
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
    repository = (BaseRepositoryImpl<Row, String>) proxy.getProxy();
    service = new BaseServiceImpl<>(repository, null);
    scope("ALL");
  }

  private void row(
      String title,
      int amount,
      boolean enabled,
      String dueAt,
      State state,
      String tenant,
      String user,
      boolean deleted) {
    var row = new Row();
    row.title = title;
    row.amount = amount;
    row.sort = amount;
    row.enabled = enabled;
    row.dueAt = dueAt == null ? null : Instant.parse(dueAt);
    row.state = state;
    row.setTenantId(tenant);
    row.setCreatedBy(user);
    row.setDeletedAt(deleted ? Instant.now() : null);
    session.persist(row);
  }

  private void scope(String policy) {
    var context = new AuthorizationContext();
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setUserId("user-a");
    context.setDataScopeType(policy);
    context.setDeptIds(Set.of());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    DataScopeContext.clear();
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
  }

  @AfterAll
  static void closeDatabase() {
    if (factory != null) factory.close();
  }

  private List<Integer> amounts(String field, String condition) {
    return service
        .<Row>limit(Map.of(field, condition), PageRequest.of(0, 10, Sort.by("amount")))
        .getContent()
        .stream()
        .map(row -> row.amount)
        .toList();
  }

  interface RowRepository extends org.simplepoint.api.base.BaseRepository<Row, String> {}

  interface ProxyRowRepository
      extends org.simplepoint.data.jpa.base.BaseRepository<Row, String>, RowRepository {}

  private ProxyRowRepository springRepository() {
    var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(session);
    repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    return repositories.getRepository(ProxyRowRepository.class);
  }

  @Test
  void springDataProxyDispatchesScalarScopeToJpaImplementation() {
    var proxy = springRepository();
    var page = proxy.<Row>limitScoped(
        Map.of("enabled", "equals:true", "deletedAt", "is:null"), Map.of("createdBy", "equals:user-a"),
        PageRequest.of(1, 1, Sort.by("amount")));
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).extracting(row -> row.amount).containsExactly(100);
  }

  @Test
  void springDataProxyDispatchesSetScopeToJpaImplementation() {
    var proxy = springRepository();
    var page = proxy.<Row>limitInScope(
        Map.of("amount", "than:greater:2", "deletedAt", "is:null"), "createdBy", List.of("user-a"),
        PageRequest.of(0, 10));
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(row -> row.amount).containsExactly(100);
    assertThat(proxy.limitInScope(Map.of(), "createdBy", List.of(), PageRequest.of(0, 10))
        .getTotalElements()).isZero();
  }

  @Test
  void springDataProxyDispatchesMembershipScopeToJpaImplementation() {
    var proxy = springRepository();
    var page = proxy.<Row>limitMemberships(
        Map.of("deletedAt", "is:null"), List.of(new org.simplepoint.api.base.QueryMembership(
            "createdBy", List.of("user-a"), false)), PageRequest.of(0, 10));
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(row -> row.amount).containsExactly(10);
  }

  @Test
  void serverOwnedRestrictionsRemainOutsideUserOrAndCount() {
    var scoped = new BaseServiceImpl<BaseRepositoryImpl<Row, String>, Row, String>(repository, null) {
      org.springframework.data.domain.Page<Row> query(Map<String, String> filters, int page) {
        return limitScoped(filters, Map.of("createdBy", "equals:user-a"),
            PageRequest.of(page, 1, Sort.by("amount")));
      }
    };
    var filters = Map.of("$query", """
        {"version":1,"where":{"op":"or","children":[
          {"field":"enabled","op":"equals","value":true},
          {"field":"amount","op":"equals","value":10}]}}
        """);
    assertThat(scoped.query(filters, 0).getTotalElements()).isEqualTo(2);
    assertThat(scoped.query(filters, 1).getContent()).extracting(row -> row.amount).containsExactly(100);
    assertThatThrownBy(() -> scoped.query(Map.of("createdBy", "equals:user-b"), 0))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> repository.limitScoped(Map.of(), Map.of("$query", "{}"), PageRequest.of(0, 10)))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void authorizationSetsAreLiteralUnboundedByPublicInAndCannotOverrideUserFilters() {
    row("Literal", 7, true, null, State.READY, "tenant-a", "user,a:literal", false);
    row("Comma fragment", 8, true, null, State.READY, "tenant-a", "user", false);
    session.flush();
    session.clear();
    var scoped = new BaseServiceImpl<BaseRepositoryImpl<Row, String>, Row, String>(repository, null) {
      org.springframework.data.domain.Page<Row> query(Map<String, String> filters, List<String> allowed) {
        return limitInScope(filters, "createdBy", allowed, PageRequest.of(0, 1, Sort.by("amount")));
      }
    };
    var allowed = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 150)
        .mapToObj(i -> "unused-" + i).toList());
    allowed.add("user,a:literal");
    assertThat(scoped.query(Map.of(), allowed).getContent()).extracting(row -> row.amount).containsExactly(7);
    assertThat(scoped.query(Map.of(), allowed).getTotalElements()).isEqualTo(1);
    assertThat(scoped.query(Map.of("amount", "equals:2"), allowed).getTotalElements()).isZero();
    assertThat(scoped.query(Map.of(), List.of()).getTotalElements()).isZero();
    assertThatThrownBy(() -> scoped.query(Map.of("$query", "{\"version\":1,\"where\":{\"field\":\"createdBy\",\"op\":\"is:null\"}}"),
        allowed)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> scoped.query(Map.of("amount", "in:" + String.join(",", allowed)), List.of()))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(InvalidQueryException.class);
  }

  @Test
  void numericOperatorsUseNumericValuesNotLexicalOrder() {
    assertThat(amounts("amount", "equals:10")).containsExactly(10);
    assertThat(amounts("amount", "not:equals:10")).containsExactly(2, 100);
    assertThat(amounts("amount", "than:greater:2")).containsExactly(10, 100);
    assertThat(amounts("amount", "than:less:10")).containsExactly(2);
    assertThat(amounts("amount", "than:equal:greater:10")).containsExactly(10, 100);
    assertThat(amounts("amount", "than:equal:less:10")).containsExactly(2, 10);
    assertThat(amounts("amount", "between:2,10")).containsExactly(2, 10);
    assertThat(amounts("amount", "not:between:2,10")).containsExactly(100);
    assertThat(amounts("amount", "in:2,100")).containsExactly(2, 100);
    assertThat(amounts("amount", "not:in:2,100")).containsExactly(10);
  }

  @Test
  void containsIsCaseInsensitiveLiteralTextAndPreservesColons() {
    assertThat(amounts("title", "like:ALPHA %_\\:tail")).containsExactly(2);
    assertThat(amounts("title", "not:like:%_")).containsExactly(10, 100);
    assertThat(amounts("title", "like:%")).containsExactly(2);
    assertThat(amounts("title", "equals:prefix Alpha %_\\:tail suffix")).containsExactly(2);
    assertThat(amounts("title", "Beta")).containsExactly(10);
  }

  @Test
  void datesNullBooleanAndEnumsHaveTypedSemantics() {
    assertThat(amounts("dueAt", "equals:2026-09-18T10:20:30Z")).containsExactly(2);
    assertThat(amounts("dueAt", "between:2026-09-18T10:20:30Z,2026-09-19T10:20:30Z"))
        .containsExactly(2, 10);
    assertThat(amounts("dueAt", "than:less:2026-09-19")).containsExactly(2);
    assertThat(amounts("dueAt", "is:null")).containsExactly(100);
    assertThat(amounts("dueAt", "is:not:null")).containsExactly(2, 10);
    assertThat(amounts("dueAt", "is:not:null:legacy")).containsExactly(2, 10);
    assertThat(amounts("enabled", "equals:false")).containsExactly(10);
    assertThat(amounts("enabled", "in:true,false")).containsExactly(2, 10, 100);
    assertThat(amounts("state", "in:READY")).containsExactly(2, 100);
  }

  @Test
  void pagingCountAndServerPoliciesRemainConsistent() {
    var page =
        service.<Row>limit(
            Map.of(
                "page",
                "0",
                "size",
                "1",
                "sort",
                "amount,asc",
                "amount",
                "than:equal:greater:10",
                "deletedAt",
                "is:not:null"),
            PageRequest.of(0, 1, Sort.by("amount")));
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).extracting(row -> row.title).containsExactly("Beta");
    scope("SELF");
    var own = service.<Row>limit(Map.of(), PageRequest.of(0, 1));
    assertThat(own.getTotalElements()).isEqualTo(2);
    assertThat(amounts("amount", "equals:10")).isEmpty();
    scope("NONE");
    assertThat(service.findAll(Map.of())).isEmpty();
  }

  @Test
  void invalidConditionsFailClosedThroughRepositoryExceptionTranslation() {
    for (var filter :
        List.of(
            Map.of("amount", "between:10,2"),
            Map.of("amount", "in:1,,2"),
            Map.of("amount", "equals:2147483648"),
            Map.of("enabled", "equals:yes"),
            Map.of("enabled", "than:greater:true"),
            Map.of("dueAt", "equals:2026-02-30"),
            Map.of("title", "unknown:x"),
            Map.of("missing", "equals:x"),
            Map.of("title", "in:" + "x,".repeat(100) + "x"),
            Map.of("amount", "like:1"))) {
      assertThatThrownBy(() -> service.findAll(filter)).isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> repository.findAll(Map.of("amount", "equals:no")))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  void structuredBooleanTreeCannotWeakenMandatoryPolicies() {
    String where =
        """
        {"version":1,"where":{"op":"or","children":[
          {"op":"and","children":[{"field":"amount","op":"than:greater","value":1},
            {"field":"amount","op":"than:less","value":20}]},
          {"op":"not","children":[{"field":"enabled","op":"equals","value":false}]}
        ]}}
        """;
    assertThat(amounts("$query", where)).containsExactly(2, 10, 100);
    scope("SELF");
    assertThat(amounts("$query", where)).containsExactly(2, 100);
    scope("NONE");
    assertThat(amounts("$query", where)).isEmpty();
  }

  @Test
  void structuredArraysPreserveCommaAndEmptyStringAndEnforceCapabilities() {
    row("a,b:c", 7, true, null, State.READY, "tenant-a", "user-a", false);
    row("", 8, true, null, State.READY, "tenant-a", "user-a", false);
    assertThat(
            amounts(
                "$query",
                """
                {"version":1,"where":{"field":"title","op":"in","value":["a,b:c",""]}}
                """))
        .containsExactly(7, 8);
    for (String field : List.of("tenantId", "createdBy", "deletedAt")) {
      String json =
          """
          {"version":1,"where":{"field":"%s","op":"is:null"}}
          """
              .formatted(field);
      assertThatThrownBy(() -> amounts("$query", json)).isInstanceOf(InvalidQueryException.class);
    }
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    context.setFieldPermissions(Map.of(Row.class.getName() + "#title", "MASKED"));
    assertThatThrownBy(
            () ->
                amounts(
                    "$query",
                    """
                    {"version":1,"where":{"field":"title","op":"equals","value":"Beta"}}
                    """))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> service.limit(Map.of(), PageRequest.of(0, 10, Sort.by("title"))))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  void existsIsCorrelatedScopedAndDoesNotDuplicatePageOrCount() {
    var beta = service.findAll(Map.of("title", "equals:Beta")).getFirst();
    for (String tenant : List.of("tenant-a", "tenant-a", "tenant-b")) {
      var label = new Label();
      label.rowId = beta.getId();
      label.label = tenant.equals("tenant-a") ? "match" : "foreign";
      label.setTenantId(tenant);
      label.setCreatedBy("user-b");
      session.persist(label);
    }
    String json =
        """
{"version":1,"where":{"op":"exists","field":"labels","where":{"field":"label","op":"equals","value":"match"}}}
""";
    var page = service.<Row>limit(Map.of("$query", json), PageRequest.of(0, 1));
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(r -> r.title).containsExactly("Beta");
    assertThat(amounts("$query", json.replace("match", "foreign"))).isEmpty();
    scope("SELF");
    assertThat(amounts("$query", json)).isEmpty();
    scope("ALL");
    assertThatThrownBy(() -> amounts("$query", json.replace("labels", "arbitrary")))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  void multiSortUsesNullsLastAndUniqueTieBreaker() {
    var page =
        service.<Row>limit(
            Map.of(),
            PageRequest.of(0, 2, Sort.by(Sort.Order.desc("dueAt"), Sort.Order.asc("amount"))));
    assertThat(page.getContent())
        .extracting(r -> r.title)
        .containsExactly("Beta", "prefix Alpha %_\\:tail suffix");
    assertThat(page.getTotalElements()).isEqualTo(3);
    var next =
        service.<Row>limit(
            Map.of(),
            PageRequest.of(1, 2, Sort.by(Sort.Order.desc("dueAt"), Sort.Order.asc("amount"))));
    assertThat(next.getContent()).extracting(r -> r.title).containsExactly("Gamma");
  }

  @Test
  void tenThousandRowBenchmarkKeepsPageAndStatementCountsBounded() {
    for (int i = 0; i < 10_000; i++) {
      row("bench-" + i, i, true, null, State.READY, "tenant-a", "user-a", false);
      if (i % 100 == 99) {
        session.flush();
        session.clear();
      }
    }
    var statistics = factory.getStatistics();
    statistics.setStatisticsEnabled(true);
    long[] timings = new long[10];
    for (int i = 0; i < timings.length; i++) {
      session.clear();
      statistics.clear();
      long start = System.nanoTime();
      var page =
          service.<Row>limit(
              Map.of("amount", "than:equal:greater:0"), PageRequest.of(450, 20, Sort.by("amount")));
      timings[i] = System.nanoTime() - start;
      assertThat(page.getContent()).hasSize(20);
      assertThat(page.getTotalElements()).isEqualTo(10_003);
      assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
      assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(20);
    }
    java.util.Arrays.sort(timings);
    System.out.printf(
        "QUERY_BENCH rows=10003 offset=9000 size=20 samples=10 median_ms=%.2f p95_ms=%.2f"
            + " max_statements=2%n",
        timings[5] / 1_000_000.0, timings[9] / 1_000_000.0);
    statistics.setStatisticsEnabled(false);
  }

  @RestController
  static class Endpoint {
    private final BaseServiceImpl<BaseRepositoryImpl<Row, String>, Row, String> service;

    Endpoint(BaseServiceImpl<BaseRepositoryImpl<Row, String>, Row, String> service) {
      this.service = service;
    }

    @GetMapping("/query-test")
    Object query(
        @RequestParam Map<String, String> filters,
        org.springframework.data.domain.Pageable pageable) {
      var result = service.<Row>limit(filters, pageable);
      return Map.of(
          "amounts",
          result.map(row -> row.amount).getContent(),
          "total",
          result.getTotalElements());
    }
  }

  @Test
  void realQueryErrorsReachHttp400WithoutLeakingSql() throws Exception {
    MockMvcBuilders.standaloneSetup(new Endpoint(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(
            new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
        .build()
        .perform(
            get("/query-test")
                .param("amount", "equals:private-input")
                .requestAttr(
                    RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
                    org.simplepoint.core.AuthorizationContextHolder.getContext()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
        .andExpect(jsonPath("$.message").value("筛选值与字段类型不匹配"));
  }

  @Test
  void httpPaginationNeverBecomesAFilterOnTheSameNamedEntityField() throws Exception {
    row("Home", 11, true, null, State.READY, "tenant-a", "user-a", false);
    row("Home next", 12, true, null, State.READY, "tenant-a", "user-a", false);
    String advanced =
        """
        {"version":1,"where":{"op":"and","children":[
          {"field":"title","op":"like","value":"Ho"}]}}
        """;
    var mvc =
        MockMvcBuilders.standaloneSetup(new Endpoint(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(
                new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
            .build();
    mvc.perform(
            get("/query-test")
                .param("page", "0")
                .param("size", "20")
                .param("$query", advanced)
                .param("sort", "sort,desc", "title,asc")
                .requestAttr(
                    RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
                    org.simplepoint.core.AuthorizationContextHolder.getContext()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amounts[0]").value(12))
        .andExpect(jsonPath("$.amounts[1]").value(11))
        .andExpect(jsonPath("$.total").value(2));

    // A filter on the business 'sort' column remains possible through the structured document.
    assertThat(
            amounts(
                "$query",
                """
                {"version":1,"where":{"field":"sort","op":"equals","value":11}}
                """))
        .containsExactly(11);
  }
}
