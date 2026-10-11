package org.simplepoint.core.permission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.springframework.security.access.AccessDeniedException;

class PageDataPermissionTest {
  @PermissionModel(page = "records.view", owner = "author", department = "org")
  record Row(String author, String org, String status) {}
  AuthorizationContext context;
  @BeforeEach void setup() {
    context = new AuthorizationContext(); context.setUserId("reader");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    ResourcePolicyContext.set("records.view");
  }
  @AfterEach void clear() {
    ResourcePolicyContext.clear(); RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }
  void scope(ResourceDataScope rule) {
    context = new AuthorizationContext(); context.setUserId("reader");
    context.setResourceDataScopes(Map.of("records.view", rule));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
  @Test void customMappingsEnforceOwnershipAndDepartmentAlternatives() {
    scope(new ResourceDataScope("SELF", Set.of(), false));
    assertTrue(PageDataPermission.allows(new Row("reader", "outside", "draft")));
    assertThrows(AccessDeniedException.class, () -> PageDataPermission.requireAccessible(new Row("other", "org-a", "draft")));
    scope(new ResourceDataScope("DEPT_AND_BELOW", Set.of("org-a", "org-child"), true));
    assertTrue(PageDataPermission.allows(new Row("other", "org-child", "draft")));
    assertTrue(PageDataPermission.allows(new Row("reader", "outside", "draft")));
    assertFalse(PageDataPermission.allows(new Row("other", "outside", "draft")));
  }
  @Test void expressionsUseTheResolvedUserAndDoNotTurnNullIntoAccess() {
    scope(new ResourceDataScope("DENY", Set.of(), false, List.of("author = $user.id AND status = \"published\"")));
    assertTrue(PageDataPermission.allows(new Row("reader", null, "published")));
    assertFalse(PageDataPermission.allows(new Row("reader", null, "draft")));
    assertFalse(PageDataPermission.allows(new Row(null, null, "published")));
    var missingUser = new AuthorizationContext(); missingUser.setResourceDataScopes(context.getResourceDataScopes());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, missingUser);
    assertFalse(PageDataPermission.allows(new Row(null, null, "published")));
  }
  @Test void denyDoesNotNeedOwnerFieldsAndMissingRequiredMappingsFailClosed() {
    scope(new ResourceDataScope("DENY", Set.of(), false));
    assertFalse(PageDataPermission.allows(new Object()));
    scope(new ResourceDataScope("SELF", Set.of(), false));
    assertThrows(AccessDeniedException.class, () -> PageDataPermission.allows(new Object()));
  }
}
