package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserDirectoryPage;
import org.simplepoint.security.entity.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class UserDirectoryDatabaseTest {
  private SessionFactory factory;
  private Session session;
  private UserDirectoryImpl directory;

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:directory-" + UUID.randomUUID())
            .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.generate_statistics", true)
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(User.class)
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    directory = new UserDirectoryImpl(session);
    authenticate("client_credentials");
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (session != null) {
      if (session.getTransaction().isActive()) {
        session.getTransaction().rollback();
      }
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void returnsOnlyScalarIdentityFieldsWithoutLoadingCredentials() throws Exception {
    var user = user("客户担当", "customer@example.com", true);
    user.setPassword("test-only-password-hash");
    user.setTwoFactorSecret("test-only-mfa-secret");
    user.setSuperAdmin(true);
    flushAndClear();

    var result = directory.findById(user.getId()).orElseThrow();
    assertThat(result.name()).isEqualTo("客户担当");
    assertThat(result.email()).isEqualTo("customer@example.com");
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    var mapper = new ObjectMapper();
    var json = mapper.writeValueAsString(new UserDirectoryPage(List.of(result), 1));
    assertThat(json).doesNotContain("password", "twoFactor", "superAdmin", "test-only");
    assertThat(mapper.readValue(json, UserDirectoryPage.class).content()).containsExactly(result);
  }

  @Test
  void pagesActiveUsersWithStableTieBreakerAndAccurateCount() {
    final var first = user("相同姓名", "team-a@example.com", true);
    final var second = user("相同姓名", "team-b@example.com", true);
    user("A disabled", "team-disabled@example.com", false);
    user("A deleted", "team-deleted@example.com", true).setDeletedAt(Instant.now());
    flushAndClear();

    var ids = List.of(first.getId(), second.getId()).stream().sorted().toList();
    var page = directory.search("TEAM", 0, 1);
    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.content()).extracting(entry -> entry.id()).containsExactly(ids.get(0));
    assertThat(directory.search("team", 1, 1).content())
        .extracting(entry -> entry.id())
        .containsExactly(ids.get(1));
    var empty = directory.search("team", 2, 1);
    assertThat(empty.content()).isEmpty();
    assertThat(empty.totalElements()).isEqualTo(2);
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
  }

  @Test
  void prefixIsLiteralAndSupportsUnicodeAndPhoneSearch() {
    final var literal = user("Literal", "ab%_!@example.com", true);
    user("Broad", "abcdef@example.com", true);
    final var unicode = user("Unicode", "日本語@example.com", true);
    var phone = user("Phone", "phone@example.com", true);
    phone.setPhoneNumber("13912345678");
    flushAndClear();

    assertThat(directory.search("ab%_!", 0, 20).content())
        .extracting(entry -> entry.id())
        .containsExactly(literal.getId());
    assertThat(directory.search("日本語", 0, 20).content())
        .extracting(entry -> entry.id())
        .containsExactly(unicode.getId());
    assertThat(directory.search("139", 0, 20).content())
        .extracting(entry -> entry.id())
        .containsExactly(phone.getId());
    factory.getStatistics().clear();
    assertThat(directory.search("𠮷𠮷", 0, 20).content()).isEmpty();
    assertThat(factory.getStatistics().getQueryExecutionCount()).isZero();
  }

  @Test
  void snapshotDistinguishesDisabledFromMissingAndDeletedUsers() {
    var disabled = user("Disabled", "disabled@example.com", false);
    var deleted = user("Deleted", "deleted@example.com", true);
    deleted.setDeletedAt(Instant.now());
    flushAndClear();

    assertThat(directory.findById(disabled.getId()))
        .hasValueSatisfying(entry -> assertThat(entry.enabled()).isFalse());
    assertThat(directory.findById(deleted.getId())).isEmpty();
    assertThat(directory.findById("missing")).isEmpty();
  }

  @Test
  void rejectsInvalidBoundsAndDoesNotEnumerateShortPrefixes() {
    assertThat(directory.search(null, 0, 20).content()).isEmpty();
    assertThat(directory.search(" ab ", 0, 20).content()).isEmpty();
    assertThatThrownBy(() -> directory.search("abc", -1, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.search("abc", 0, 51))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.search("abc", 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.search("abc", Integer.MAX_VALUE, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.search("𠮷".repeat(321), 0, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.findById(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.findById("x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(factory.getStatistics().getQueryExecutionCount()).isZero();
  }

  @Test
  void scopeOrServiceNameCannotReplaceAuthenticatedServiceClaims() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> directory.findById("user")).isInstanceOf(AccessDeniedException.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken("dna", null, "SCOPE_service-router.invoke"));
    assertThatThrownBy(() -> directory.search("abc", 0, 20))
        .isInstanceOf(AccessDeniedException.class);
    authenticate("authorization_code");
    assertThatThrownBy(() -> directory.findById("user")).isInstanceOf(AccessDeniedException.class);
    assertThat(factory.getStatistics().getQueryExecutionCount()).isZero();
  }

  private User user(String name, String email, boolean enabled) {
    var user = new User();
    user.setName(name);
    user.setEmail(email);
    user.setEnabled(enabled);
    session.persist(user);
    return user;
  }

  private void flushAndClear() {
    session.flush();
    session.clear();
    factory.getStatistics().clear();
  }

  private void authenticate(String grant) {
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            "display", Map.of("client_id", "dna", "grant_type", grant), List.of());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(principal, null, "SCOPE_service-router.invoke"));
  }
}
