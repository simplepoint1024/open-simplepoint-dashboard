package org.simplepoint.plugin.rbac.tenant.api.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AuditIdentityRequestTest {
  @Test
  void boundsTheCombinedBatchAndCopiesCallerCollections() {
    Set<String> users =
        IntStream.range(0, 99).mapToObj(i -> "user-" + i).collect(Collectors.toSet());
    var request = new AuditIdentityRequest("tenant", true, users, Set.of("department"));
    users.clear();
    assertThat(request.userIds()).hasSize(99);
    assertThatThrownBy(() -> request.userIds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> new AuditIdentityRequest("tenant", true, request.userIds(), Set.of("a", "b")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AuditIdentityRequest("tenant", false, null, null).userIds()).isEmpty();
  }

  @Test
  void rejectsInvalidIdentitiesWithoutChangingOpaqueIds() {
    for (String invalid :
        new String[] {null, "", " ", "***", " tenant", "tenant ", "x".repeat(65)}) {
      assertThatThrownBy(() -> new AuditIdentityRequest(invalid, false, Set.of(), Set.of()))
          .isInstanceOf(IllegalArgumentException.class);
      var ids = new HashSet<String>();
      ids.add(invalid);
      assertThatThrownBy(() -> new AuditIdentityRequest("tenant", false, ids, Set.of()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new AuditIdentityRequest("tenant", false, Set.of(), ids))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new AuditIdentityRequest("租户", false, Set.of("员工・日本語"), null).userIds())
        .containsExactly("员工・日本語");
  }

  @Test
  void roleAuthoritiesAreBoundedWithOtherReferencesAndWireDefaultsStayCompatible()
      throws Exception {
    String authority = "𠮷".repeat(100);
    var request =
        new AuditIdentityRequest("tenant", false, Set.of("user"), Set.of(), Set.of(authority));
    assertThat(request.roleAuthorities()).containsExactly(authority);
    assertThatThrownBy(
            () ->
                new AuditIdentityRequest(
                    "tenant", false, Set.of(), Set.of(), Set.of(authority + "x")))
        .isInstanceOf(IllegalArgumentException.class);
    Set<String> roles =
        IntStream.range(0, 100).mapToObj(i -> "role-" + i).collect(Collectors.toSet());
    assertThatThrownBy(
            () -> new AuditIdentityRequest("tenant", false, Set.of("user"), Set.of(), roles))
        .isInstanceOf(IllegalArgumentException.class);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsString(request), AuditIdentityRequest.class))
        .isEqualTo(request);
    assertThat(
            mapper
                .readValue(
                    "{\"tenantId\":\"tenant\",\"userIds\":[\"user\"]}", AuditIdentityRequest.class)
                .roleAuthorities())
        .isEmpty();
  }
}
