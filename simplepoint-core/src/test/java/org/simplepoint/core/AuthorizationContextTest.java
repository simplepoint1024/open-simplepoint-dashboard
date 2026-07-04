package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class AuthorizationContextTest {

  private AuthorizationContext ctx;

  @BeforeEach
  void setUp() {
    ctx = new AuthorizationContext();
  }

  // -------- write-once setters --------

  @Test
  void setContextId_setsWhenNull() {
    ctx.setContextId("ctx-1");
    assertThat(ctx.getContextId()).isEqualTo("ctx-1");
  }

  @Test
  void setContextId_doesNotOverwriteExistingValue() {
    ctx.setContextId("first");
    ctx.setContextId("second");
    assertThat(ctx.getContextId()).isEqualTo("first");
  }

  @Test
  void setUserId_setsWhenNull() {
    ctx.setUserId("user-1");
    assertThat(ctx.getUserId()).isEqualTo("user-1");
  }

  @Test
  void setUserId_doesNotOverwriteExistingValue() {
    ctx.setUserId("original");
    ctx.setUserId("replacement");
    assertThat(ctx.getUserId()).isEqualTo("original");
  }

  @Test
  void setIsAdministrator_setsWhenNull() {
    ctx.setIsAdministrator(true);
    assertThat(ctx.getIsAdministrator()).isTrue();
  }

  @Test
  void setIsAdministrator_doesNotOverwriteExistingValue() {
    ctx.setIsAdministrator(true);
    ctx.setIsAdministrator(false);
    assertThat(ctx.getIsAdministrator()).isTrue();
  }

  @Test
  void setRoles_setsWhenNull() {
    ctx.setRoles(Arrays.asList("ADMIN", "USER"));
    assertThat(ctx.getRoles()).containsExactlyInAnyOrder("ADMIN", "USER");
  }

  @Test
  void setRoles_withNullInput_setsEmptyCollection() {
    ctx.setRoles(null);
    assertThat(ctx.getRoles()).isEmpty();
  }

  @Test
  void setRoles_doesNotOverwriteExistingValue() {
    ctx.setRoles(Arrays.asList("ADMIN"));
    ctx.setRoles(Arrays.asList("USER"));
    assertThat(ctx.getRoles()).containsExactly("ADMIN");
  }

  @Test
  void setResources_setsWhenNull() {
    ctx.setResources(Arrays.asList("read", "write"));
    assertThat(ctx.getResources()).containsExactlyInAnyOrder("read", "write");
  }

  @Test
  void setResources_withNullInput_setsEmptyCollection() {
    ctx.setResources(null);
    assertThat(ctx.getResources()).isEmpty();
  }

  @Test
  void setResources_doesNotOverwriteExistingValue() {
    ctx.setResources(Arrays.asList("read"));
    ctx.setResources(Arrays.asList("write"));
    assertThat(ctx.getResources()).containsExactly("read");
  }

  @Test
  void setVersion_setsWhenNull() {
    ctx.setVersion(42L);
    assertThat(ctx.getVersion()).isEqualTo(42L);
  }

  @Test
  void setVersion_doesNotOverwriteExistingValue() {
    ctx.setVersion(1L);
    ctx.setVersion(99L);
    assertThat(ctx.getVersion()).isEqualTo(1L);
  }

  @Test
  void setAttributes_setsWhenNull() {
    Map<String, String> attrs = new HashMap<>();
    attrs.put("key", "value");
    ctx.setAttributes(attrs);
    assertThat(ctx.getAttributes()).containsEntry("key", "value");
  }

  @Test
  void setAttributes_withNullInput_setsEmptyMap() {
    ctx.setAttributes(null);
    assertThat(ctx.getAttributes()).isEmpty();
  }

  @Test
  void setAttributes_doesNotOverwriteExistingValue() {
    Map<String, String> original = new HashMap<>();
    original.put("k", "v1");
    ctx.setAttributes(original);
    Map<String, String> replacement = new HashMap<>();
    replacement.put("k", "v2");
    ctx.setAttributes(replacement);
    assertThat(ctx.getAttribute("k")).isEqualTo("v1");
  }

  @Test
  void setScopeType_setsWhenNull() {
    ctx.setScopeType(AuthorizationScopeType.PLATFORM);
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PLATFORM);
  }

  @Test
  void setScopeType_withString_setsWhenNull() {
    ctx.setScopeType("TENANT");
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
  }

  @Test
  void setScopeType_doesNotOverwriteExistingValue() {
    ctx.setScopeType(AuthorizationScopeType.PERSONAL);
    ctx.setScopeType(AuthorizationScopeType.PLATFORM);
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PERSONAL);
  }

  @Test
  void replaceScopeType_overwritesExistingValue() {
    ctx.setScopeType(AuthorizationScopeType.PERSONAL);
    ctx.replaceScopeType(AuthorizationScopeType.PLATFORM);
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.PLATFORM);
  }

  @Test
  void replaceScopeType_withString_overwritesExistingValue() {
    ctx.setScopeType(AuthorizationScopeType.PERSONAL);
    ctx.replaceScopeType("TENANT");
    assertThat(ctx.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
  }

  @Test
  void setActorRole_setsWhenNull() {
    ctx.setActorRole(AuthorizationActorRole.TENANT_OWNER);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_OWNER);
  }

  @Test
  void setActorRole_withString_setsWhenNull() {
    ctx.setActorRole("PLATFORM_ADMIN");
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PLATFORM_ADMIN);
  }

  @Test
  void setActorRole_doesNotOverwriteExistingValue() {
    ctx.setActorRole(AuthorizationActorRole.TENANT_MEMBER);
    ctx.setActorRole(AuthorizationActorRole.TENANT_OWNER);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_MEMBER);
  }

  @Test
  void replaceActorRole_overwritesExistingValue() {
    ctx.setActorRole(AuthorizationActorRole.TENANT_MEMBER);
    ctx.replaceActorRole(AuthorizationActorRole.TENANT_OWNER);
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.TENANT_OWNER);
  }

  @Test
  void replaceActorRole_withString_overwritesExistingValue() {
    ctx.setActorRole(AuthorizationActorRole.TENANT_MEMBER);
    ctx.replaceActorRole("PLATFORM_ADMIN");
    assertThat(ctx.getActorRole()).isEqualTo(AuthorizationActorRole.PLATFORM_ADMIN);
  }

  @Test
  void mergeAttributes_overwritesExistingAndAddsNewValues() {
    ctx.setAttributes(Collections.singletonMap("X-Tenant-Id", "old-tenant"));

    ctx.mergeAttributes(Map.of("X-Tenant-Id", "new-tenant", "X-Context-Id", "ctx-1"));

    assertThat(ctx.getAttributes())
        .containsEntry("X-Tenant-Id", "new-tenant")
        .containsEntry("X-Context-Id", "ctx-1");
  }

  @Test
  void mergeAttributes_worksAfterNullAttributesWereSet() {
    ctx.setAttributes(null);

    ctx.mergeAttributes(Map.of("X-Tenant-Id", "tenant-1"));

    assertThat(ctx.getAttribute("X-Tenant-Id")).isEqualTo("tenant-1");
  }

  // -------- getAttribute --------

  @Test
  void getAttribute_existingKey_returnsValue() {
    Map<String, String> attrs = Collections.singletonMap("tenant", "t1");
    ctx.setAttributes(attrs);
    assertThat(ctx.getAttribute("tenant")).isEqualTo("t1");
  }

  @Test
  void getAttribute_missingKey_returnsNull() {
    Map<String, String> attrs = Collections.singletonMap("tenant", "t1");
    ctx.setAttributes(attrs);
    assertThat(ctx.getAttribute("missing")).isNull();
  }

  @Test
  void getAttribute_whenAttributesNotSet_returnsNull() {
    assertThat(ctx.getAttribute("anything")).isNull();
  }

  // -------- asAuthorities --------

  @Test
  void asAuthorities_administrator_includesAdminRole() {
    ctx.setIsAdministrator(true);
    var authorities = ctx.asAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
    assertThat(authorities).contains("ROLE_Administrator");
  }

  @Test
  void asAuthorities_notAdministrator_doesNotIncludeAdminRole() {
    ctx.setIsAdministrator(false);
    var authorities = ctx.asAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
    assertThat(authorities).doesNotContain("ROLE_Administrator");
  }

  @Test
  void asAuthorities_withRoles_prefixesWithRole() {
    ctx.setRoles(Arrays.asList("ADMIN", "USER"));
    var authorities = ctx.asAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
    assertThat(authorities).contains("ROLE_ADMIN", "ROLE_USER");
  }

  @Test
  void asAuthorities_withResources_includesResourceCodesAsIs() {
    ctx.setResources(Arrays.asList("users.read", "users.write"));
    var authorities = ctx.asAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
    assertThat(authorities).contains("users.read", "users.write");
  }

  @Test
  void asAuthorities_allNull_returnsEmptyCollection() {
    assertThat(ctx.asAuthorities()).isEmpty();
  }

  @Test
  void asAuthorities_combined_returnsAllAuthorities() {
    ctx.setIsAdministrator(true);
    ctx.setRoles(Arrays.asList("EDITOR"));
    ctx.setResources(Arrays.asList("posts.create"));
    var authorities = ctx.asAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
    assertThat(authorities).contains("ROLE_Administrator", "ROLE_EDITOR", "posts.create");
  }
}
