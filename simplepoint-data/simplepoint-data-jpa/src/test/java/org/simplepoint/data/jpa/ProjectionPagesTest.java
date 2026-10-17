package org.simplepoint.data.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class ProjectionPagesTest {
  @Entity(name = "ProjectionPageRow")
  public static class Row {
    @Id String id;
    String tenant;
    String title;
    int quantity;
    boolean deleted;
  }

  @TableQuery(
      value = {@QueryField("title"), @QueryField("quantity")},
      maxPageSize = 10)
  record Summary(String id, String title, int quantity) {}

  private static SessionFactory factory;
  private Session session;

  @Entity(name = "NewestProjectionRow")
  public static class NewestRow {
    @Id String id;
    String tenant;
    String title;
    int quantity;
    java.time.Instant createdAt;
  }


  @BeforeAll
  static void database() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:projection-pages;DB_CLOSE_DELAY=-1")
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(Row.class)
            .addAnnotatedClass(NewestRow.class)
            .buildMetadata()
            .buildSessionFactory();
  }

  @BeforeEach
  void data() {
    session = factory.openSession();
    session.beginTransaction();
    for (int index = 0; index < 30; index++) {
      var row = new Row();
      row.id = "%02d".formatted(index);
      row.tenant = index < 24 ? "a" : "b";
      row.deleted = index == 23;
      row.title = "Item " + index;
      row.quantity = index / 3;
      session.persist(row);
    }
    session.flush();
    session.clear();
  }

  @AfterEach
  void closeSession() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    session.getTransaction().rollback();
    session.close();
  }

  @AfterAll
  static void closeDatabase() {
    factory.close();
  }

  private Page<Summary> query(Map<String, String> filters, Pageable page) {
    return ProjectionPages.query(
        session,
        Row.class,
        Summary.class,
        filters,
        page,
        "quantity",
        Set.of("title"),
        (cb, criteria, root) ->
            cb.and(cb.equal(root.get("tenant"), "a"), cb.isFalse(root.get("deleted"))),
        row -> new Summary(row.id, row.title, row.quantity));
  }

  @Test
  void databaseFiltersBeforePagingAndCountsUseTheSameMandatoryBoundary() {
    var result =
        query(
            Map.of("title", "like:ITEM", "quantity", "than:equal:greater:5"),
            PageRequest.of(1, 3, Sort.by("quantity")));
    assertThat(result.getTotalElements()).isEqualTo(8);
    assertThat(result.getContent()).extracting(Summary::id).containsExactly("18", "19", "20");
    assertThat(
            query(
                    Map.of("quantity", "than:equal:greater:5"),
                    PageRequest.of(2, 3, Sort.by("quantity")))
                .getContent())
        .extracting(Summary::id)
        .containsExactly("21", "22");
  }

  @Test
  void defaultOrderCannotLeakHiddenFieldOrderingAndExplicitHiddenSortIsRejected() {
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("Summary#quantity", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThat(query(null, PageRequest.of(0, 2)).getContent())
        .extracting(Summary::id)
        .containsExactly("00", "01");
    assertThatThrownBy(() -> query(Map.of(), PageRequest.of(0, 2, Sort.by("quantity"))))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  void invalidFieldsLimitsAndMissingAuthorizationAreFailClosed() {
    assertThatThrownBy(() -> query(Map.of("tenant", "equals:b"), PageRequest.of(0, 10)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> query(Map.of(), PageRequest.of(0, 11)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> query(Map.of(), Pageable.unpaged()))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(
            () ->
                ProjectionPages.query(
                    session,
                    Row.class,
                    Summary.class,
                    Map.of(),
                    PageRequest.of(0, 2),
                    "quantity",
                    Set.of(),
                    null,
                    row -> new Summary(row.id, row.title, row.quantity)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authorization boundary");
    assertThatThrownBy(
            () ->
                ProjectionPages.query(
                    session,
                    Row.class,
                    Summary.class,
                    Map.of(),
                    PageRequest.of(0, 2),
                    "quantity",
                    Set.of(),
                    (cb, criteria, root) -> null,
                    row -> new Summary(row.id, row.title, row.quantity)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test void newestDefaultIsAppliedBeforePagingAndKeepsScopeAndExplicitSorting() {
    for (int index = 0; index < 12; index++) {
      var row = new NewestRow();
      row.id = "%02d".formatted(99 - index);
      row.tenant = index < 9 ? "visible" : "other";
      row.title = "Record " + index;
      row.quantity = index;
      row.createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index);
      session.persist(row);
    }
    session.flush();
    java.util.function.Function<Pageable, Page<Summary>> read = page -> ProjectionPages.query(
        session, NewestRow.class, Summary.class, Map.of(), page, "quantity", Set.of(),
        (cb, query, root) -> cb.equal(root.get("tenant"), "visible"),
        row -> new Summary(row.id, row.title, row.quantity));
    var first = read.apply(PageRequest.of(0, 4));
    assertThat(first.getTotalElements()).isEqualTo(9);
    assertThat(first.getContent()).extracting(Summary::quantity).containsExactly(8, 7, 6, 5);
    assertThat(read.apply(PageRequest.of(1, 4)).getContent()).extracting(Summary::quantity).containsExactly(4, 3, 2, 1);
    assertThat(read.apply(PageRequest.of(0, 4, Sort.by("quantity"))).getContent()).extracting(Summary::quantity).containsExactly(0, 1, 2, 3);
  }
}
