package org.simplepoint.plugin.personal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.personal.api.PersonalInboxGateway;
import org.simplepoint.plugin.personal.api.PersonalModels.ActionInput;
import org.simplepoint.plugin.personal.api.PersonalModels.ChecklistEntry;
import org.simplepoint.plugin.personal.api.PersonalModels.Delivery;
import org.simplepoint.plugin.personal.api.PersonalModels.DeliveryTarget;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemInput;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemView;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoInput;
import org.simplepoint.plugin.personal.api.PersonalService;
import org.simplepoint.plugin.personal.repository.PersonalRepository;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Real Hibernate transactions, optimistic locking and SQL ownership predicates, not repository
 * mocks.
 */
class PersonalServiceImplTest {
  private static LocalContainerEntityManagerFactoryBean factory;
  private static PersonalService service;
  private static PersonalInboxGateway gateway;
  private String user;

  @BeforeAll
  static void database() {
    factory = new LocalContainerEntityManagerFactoryBean();
    String url = System.getenv("PERSONAL_TEST_DB_URL");
    if (url != null && url.isBlank()) url = null;
    if (url != null && !url.endsWith("/personal_test"))
      throw new IllegalArgumentException("Only disposable personal_test database is allowed");
    factory.setDataSource(
        new DriverManagerDataSource(
            url == null ? "jdbc:h2:mem:personal;DB_CLOSE_DELAY=-1" : url,
            url == null ? "sa" : "personal_test",
            url == null ? "" : "personal_test"));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setPackagesToScan("org.simplepoint.plugin.personal.api.entity");
    factory.setJpaPropertyMap(
        Map.of(
            "hibernate.hbm2ddl.auto",
            "create-drop",
            "hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
    factory.afterPropertiesSet();
    var emf = factory.getObject();
    var repository =
        new PersonalRepository(SharedEntityManagerCreator.createSharedEntityManager(emf));
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(new JpaTransactionManager(emf));
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var repositoryProxy = new ProxyFactory(repository);
    repositoryProxy.setProxyTargetClass(true);
    repositoryProxy.addAdvice(
        new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
            new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
    var proxy =
        new ProxyFactory(new PersonalServiceImpl((PersonalRepository) repositoryProxy.getProxy()));
    proxy.addAdvice(interceptor);
    var bean = proxy.getProxy();
    service = (PersonalService) bean;
    gateway = (PersonalInboxGateway) bean;
  }

  @BeforeEach
  void setup() {
    user = UUID.randomUUID().toString();
    context(user, "tenant-a");
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @AfterAll
  static void closeDatabase() {
    if (factory != null) factory.destroy();
  }

  private void context(String owner, String tenant) {
    var context = new AuthorizationContext();
    context.setUserId(owner);
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private ItemInput input(Long version, String title, List<ChecklistEntry> checklist) {
    return new ItemInput(
        version, title, "说明", "开发", 2, null, Instant.now().minusSeconds(60), false, checklist);
  }

  private ItemView create(String title) {
    return service.createItem(input(null, title, List.of()));
  }

  private ItemView action(ItemView item, String action) {
    return service.itemAction(item.id(), new ActionInput(item.version(), action, null));
  }

  @Test
  void itemLifecyclePersistsHistoryAndCompletionMessage() {
    var created = create("完整生命周期");
    assertThat(service.item(created.id()).title()).isEqualTo("完整生命周期");
    var started = action(created, "START");
    assertThat(started.status()).isEqualTo("IN_PROGRESS");
    var done = action(started, "COMPLETE");
    assertThat(done.completedAt()).isNotNull();
    assertThat(service.overview().unreadMessages()).isEqualTo(1);
    var archived = action(done, "ARCHIVE");
    assertThat(archived.archived()).isTrue();
    assertThat(
            service
                .items(null, null, null, null, "ARCHIVED", PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(1);
    var deleted = action(archived, "DELETE");
    assertThatThrownBy(() -> service.item(deleted.id()))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThat(
            service
                .items(null, null, null, null, "TRASH", PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(1);
    var restored = action(deleted, "RESTORE");
    var unarchived = action(restored, "UNARCHIVE");
    assertThat(action(unarchived, "REOPEN").status()).isEqualTo("TODO");
    assertThat(service.history(created.id(), PageRequest.of(0, 20)).getTotalElements())
        .isEqualTo(8);
  }

  @Test
  void userAndWorkspaceIsolationIncludesDetailsHistoryListsAndActions() {
    var item = create("private");
    context("other-user", "tenant-a");
    assertThat(service.overview().activeItems()).isZero();
    assertThatThrownBy(() -> service.item(item.id()))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThatThrownBy(() -> action(item, "DELETE"))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThatThrownBy(() -> service.history(item.id(), PageRequest.of(0, 20)))
        .isInstanceOf(java.util.NoSuchElementException.class);
    context(user, "tenant-b");
    assertThat(service.items(null, null, null, null, "ACTIVE", PageRequest.of(0, 20))).isEmpty();
    assertThatThrownBy(() -> service.item(item.id()))
        .isInstanceOf(java.util.NoSuchElementException.class);
    context(user, "tenant-a");
    assertThat(service.item(item.id()).id()).isEqualTo(item.id());
  }

  @Test
  void optimisticVersionPreventsLostUpdateAndValidationRollsBack() {
    var original = create("original");
    var changed =
        service.updateItem(original.id(), input(original.version(), "changed", List.of()));
    assertThat(changed.version()).isGreaterThan(original.version());
    assertThatThrownBy(
            () -> service.updateItem(original.id(), input(original.version(), "stale", List.of())))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> action(original, "DELETE"))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(
            () -> service.updateItem(changed.id(), input(changed.version(), "", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(service.item(original.id()).title()).isEqualTo("changed");
    assertThat(service.history(original.id(), PageRequest.of(0, 20)).getTotalElements())
        .isEqualTo(2);
  }

  @Test
  void checklistBlocksCompletionAndRemarksAreImmutableHistory() {
    var item =
        service.createItem(
            input(null, "checklist", List.of(new ChecklistEntry("one", "子项", false))));
    assertThatThrownBy(() -> action(item, "COMPLETE")).isInstanceOf(IllegalArgumentException.class);
    var updated =
        service.updateItem(
            item.id(),
            input(item.version(), "checklist", List.of(new ChecklistEntry("one", "子项", true))));
    var noted =
        service.itemAction(updated.id(), new ActionInput(updated.version(), "COMMENT", "进展"));
    assertThat(service.history(item.id(), PageRequest.of(0, 20)).getContent())
        .anyMatch(event -> "进展".equals(event.detail()));
    assertThat(action(noted, "COMPLETE").status()).isEqualTo("DONE");
  }

  @Test
  void filteringEscapesWildcardsAndOverviewCountsOnlyActiveItems() {
    create("100%_done");
    create("100Xdone");
    assertThat(
            service.items("%_", null, "开发", 2, "ACTIVE", PageRequest.of(0, 20)).getTotalElements())
        .isEqualTo(1);
    assertThat(service.overview().overdueItems()).isEqualTo(2);
    var item =
        service
            .items(null, null, null, null, "ACTIVE", PageRequest.of(0, 20))
            .getContent()
            .getFirst();
    action(item, "CANCEL");
    assertThat(service.overview().activeItems()).isEqualTo(1);
    assertThat(service.items(null, null, null, null, "OVERDUE", PageRequest.of(0, 1000)).getSize())
        .isEqualTo(100);
  }

  @Test
  void manualTodoLifecycleAndBusinessDeliveryAreIdempotentAndSeparated() {
    var manual = service.createTodo(new TodoInput("手工", "", 1, null));
    var completed =
        service.todoAction(manual.id(), new ActionInput(manual.version(), "COMPLETE", null));
    assertThat(completed.status()).isEqualTo("DONE");
    assertThat(
            service
                .todoAction(completed.id(), new ActionInput(completed.version(), "REOPEN", null))
                .status())
        .isEqualTo("PENDING");
    var target = new DeliveryTarget(user, "TENANT:tenant-a");
    var delivery = new Delivery("WORKFLOW", "task-1", "审批", "由来源系统处理", 2, null, "/workflow/task/1");
    var todo = gateway.deliverTodo(target, delivery);
    assertThat(gateway.deliverTodo(target, delivery).id()).isEqualTo(todo.id());
    assertThatThrownBy(
            () -> service.todoAction(todo.id(), new ActionInput(todo.version(), "COMPLETE", null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(gateway.resolveTodo(target, "WORKFLOW", "task-1", "DONE").status())
        .isEqualTo("DONE");
    assertThat(gateway.resolveTodo(target, "WORKFLOW", "task-1", "DONE").status())
        .isEqualTo("DONE");
    assertThatThrownBy(() -> gateway.resolveTodo(target, "WORKFLOW", "task-1", "CANCELLED"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void messagesReadArchiveAndReadAllAreScopedAndVersioned() {
    var delivery = new Delivery("TEST", "event-1", "消息", "内容", 1, null, "/my/items");
    var message = gateway.deliverMessage(new DeliveryTarget(user, "TENANT:tenant-a"), delivery);
    assertThat(gateway.deliverMessage(new DeliveryTarget(user, "TENANT:tenant-a"), delivery).id())
        .isEqualTo(message.id());
    gateway.deliverMessage(new DeliveryTarget(user, "TENANT:tenant-b"), delivery);
    gateway.deliverMessage(new DeliveryTarget("other-user", "TENANT:tenant-a"), delivery);
    service.readAllMessages();
    assertThat(service.message(message.id()).readAt()).isNotNull();
    assertThat(service.message(message.id()).version()).isGreaterThan(message.version());
    assertThatThrownBy(
            () ->
                service.messageAction(
                    message.id(), new ActionInput(message.version(), "UNREAD", null)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var read = service.message(message.id());
    var archived =
        service.messageAction(read.id(), new ActionInput(read.version(), "ARCHIVE", null));
    assertThat(service.messages(null, false, true, PageRequest.of(0, 20)).getTotalElements())
        .isEqualTo(1);
    assertThat(archived.archived()).isTrue();
    context(user, "tenant-b");
    assertThat(service.overview().unreadMessages()).isEqualTo(1);
    context("other-user", "tenant-a");
    assertThat(service.overview().unreadMessages()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void headerFiltersAndSortingCoverTheWholeResultWithStablePagination() {
    create("gamma");
    create("alpha");
    create("beta");
    create("literal%_text");
    var sort = org.springframework.data.domain.Sort.by("title").ascending();
    var filters =
        Map.of("priority", "in:1,2", "category", "equals:开发", "status", "not:in:DONE,CANCELLED");
    var first =
        service.items(null, null, null, null, "ACTIVE", PageRequest.of(0, 1, sort), filters);
    var second =
        service.items(null, null, null, null, "ACTIVE", PageRequest.of(1, 1, sort), filters);
    assertThat(first.getTotalElements()).isEqualTo(4);
    assertThat(first.getContent().getFirst().title()).isEqualTo("alpha");
    assertThat(second.getContent().getFirst().title()).isEqualTo("beta");
    assertThat(
            service
                .items(
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    PageRequest.of(0, 1, sort.descending()),
                    filters)
                .getContent()
                .getFirst()
                .title())
        .isEqualTo("literal%_text");
    assertThat(
            service
                .items(
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    PageRequest.of(0, 20),
                    Map.of("title", "like:%_"))
                .getTotalElements())
        .isEqualTo(1);
    assertThat(
            service
                .items(
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    PageRequest.of(0, 20),
                    Map.of("title", "not:like:%_"))
                .getTotalElements())
        .isEqualTo(3);
    assertThat(
            service
                .items(null, "DONE", null, null, "ACTIVE", PageRequest.of(0, 20), filters)
                .getTotalElements())
        .isZero();
    context(user, "tenant-b");
    create("alpha");
    context("another-" + user, "tenant-a");
    create("alpha");
    context(user, "tenant-a");
    assertThat(
            service
                .items(null, null, null, null, "ACTIVE", PageRequest.of(0, 20), filters)
                .getTotalElements())
        .isEqualTo(4);
  }

  @Test
  void headerDatesNumbersNullsAndAllOperatorsAreTyped() {
    var due = Instant.parse("2026-09-18T10:30:45Z");
    service.createItem(new ItemInput(null, "dated", "", "", 2, null, due, false, List.of()));
    service.createItem(new ItemInput(null, "undated", "", "", 0, null, null, false, List.of()));
    var page = PageRequest.of(0, 20);
    for (String op :
        List.of(
            "equals:2",
            "not:equals:0",
            "in:1,2",
            "not:in:0,1",
            "than:greater:1",
            "than:less:3",
            "than:equal:greater:2",
            "than:equal:less:2",
            "between:1,3",
            "not:between:0,1")) {
      long expected = op.equals("than:less:3") || op.equals("than:equal:less:2") ? 2 : 1;
      assertThat(
              service
                  .items(null, null, null, null, "ACTIVE", page, Map.of("priority", op))
                  .getTotalElements())
          .as(op)
          .isEqualTo(expected);
    }
    for (String value :
        List.of(
            "equals:" + due,
            "than:equal:greater:" + due,
            "between:2026-09-18T00:00:00Z,2026-09-18T23:59:59Z",
            "than:greater:2026-09-17",
            "is:not:null",
            "is:null")) {
      assertThat(
              service
                  .items(null, null, null, null, "ACTIVE", page, Map.of("dueAt", value))
                  .getTotalElements())
          .as(value)
          .isEqualTo(1);
    }
    for (var direction : org.springframework.data.domain.Sort.Direction.values()) {
      assertThat(
              service
                  .items(
                      null,
                      null,
                      null,
                      null,
                      "ACTIVE",
                      PageRequest.of(
                          0, 20, org.springframework.data.domain.Sort.by(direction, "dueAt")),
                      Map.of())
                  .getContent())
          .extracting(ItemView::title)
          .containsExactly("dated", "undated");
    }
  }

  @Test
  void todoAndMessageHeadersUseTheSameScopedQueryContract() {
    service.createTodo(new TodoInput("beta", "", 1, null));
    service.createTodo(new TodoInput("alpha", "", 2, Instant.now()));
    var page = PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("title"));
    assertThat(service.todos(null, "PENDING", page, Map.of("source", "in:MANUAL")).getContent())
        .extracting(todo -> todo.title())
        .containsExactly("alpha", "beta");
    assertThat(service.todos(null, "PENDING", page, Map.of("dueAt", "is:null")).getTotalElements())
        .isEqualTo(1);
    var target = new DeliveryTarget(user, "TENANT:tenant-a");
    gateway.deliverMessage(target, new Delivery("WORKFLOW", "a", "beta", "", 1, null, null));
    gateway.deliverMessage(target, new Delivery("WORKFLOW", "b", "alpha", "", 2, null, null));
    assertThat(
            service
                .messages(
                    null,
                    true,
                    false,
                    page,
                    Map.of("source", "equals:WORKFLOW", "createdAt", "than:greater:2020-01-01"))
                .getContent())
        .extracting(message -> message.title())
        .containsExactly("alpha", "beta");
  }

  @Test
  void structuredQueriesAndTypedHeaderArraysRespectOwnerWorkspaceAndViews() {
    create("a,b:c");
    create("other");
    String query =
        """
        {"version":1,"where":{"op":"or","children":[
          {"field":"title","op":"in","value":["a,b:c"]},
          {"op":"and","children":[{"field":"priority","op":"equals","value":2},
            {"field":"title","op":"equals","value":"other"}]}
        ]}}
        """;
    var page = PageRequest.of(0, 20);
    assertThat(
            service
                .items(null, null, null, null, "ACTIVE", page, Map.of("$query", query))
                .getTotalElements())
        .isEqualTo(2);
    assertThat(
            service
                .items(null, null, null, null, "TRASH", page, Map.of("$query", query))
                .getTotalElements())
        .isZero();
    String header =
        """
        {"version":1,"where":{"field":"title","op":"in","value":["a,b:c"]}}
        """;
    assertThat(
            service
                .items(null, null, null, null, "ACTIVE", page, Map.of("title", header))
                .getTotalElements())
        .isEqualTo(1);
    context(user, "tenant-b");
    assertThat(
            service
                .items(null, null, null, null, "ACTIVE", page, Map.of("$query", query))
                .getTotalElements())
        .isZero();
    context("other-" + user, "tenant-a");
    assertThat(
            service
                .items(null, null, null, null, "ACTIVE", page, Map.of("$query", query))
                .getTotalElements())
        .isZero();
    assertThatThrownBy(
            () ->
                service.items(
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    page,
                    Map.of("$query", header.replace("title", "ownerId"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedHeadersCannotBypassOwnershipOrSilentlyDropConditions() {
    var page = PageRequest.of(0, 20);
    for (var filter :
        List.of(
            Map.of("ownerId", "equals:other"),
            Map.of("workspace", "is:not:null"),
            Map.of("title", "unknown:x"),
            Map.of("priority", "equals:NaN"),
            Map.of("dueAt", "equals:invalid"),
            Map.of("title", "between:z,a"),
            Map.of("priority", "between:1"),
            Map.of("priority", "in:1,,2"),
            Map.of("title", "like:"),
            Map.of("priority", "like:2"))) {
      assertThatThrownBy(() -> service.items(null, null, null, null, "ACTIVE", page, filter))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String field :
        List.of("ownerId", "workspace", "checklistJson", "title.length", "deletedAt")) {
      assertThatThrownBy(
              () ->
                  service.items(
                      null,
                      null,
                      null,
                      null,
                      "ACTIVE",
                      PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(field)),
                      Map.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void invalidInputsAndUnsafeBusinessLinksAreRejected() {
    assertThatThrownBy(
            () ->
                service.createItem(
                    input(
                        null,
                        "bad",
                        List.of(
                            new ChecklistEntry("x", "a", true),
                            new ChecklistEntry("x", "b", false)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.createTodo(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                gateway.deliverMessage(
                    new DeliveryTarget(user, "TENANT:tenant-a"),
                    new Delivery("TEST", "bad", "x", "", 1, null, "//evil.example")))
        .isInstanceOf(IllegalArgumentException.class);
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> service.overview())
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
}
