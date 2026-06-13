package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class AuthorizationScopeGuardsTest {

  @Test
  void requirePlatformAdministrator_allowsPlatformAdmin() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(true);
    ctx.setScopeType(AuthorizationScopeType.PLATFORM);

    AuthorizationScopeGuards.requirePlatformAdministrator(ctx);
  }

  @Test
  void requirePlatformAdministrator_rejectsTenantAdmin() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setIsAdministrator(false);
    ctx.setScopeType(AuthorizationScopeType.TENANT);
    ctx.setActorRole(AuthorizationActorRole.TENANT_ADMIN);

    assertThatThrownBy(() -> AuthorizationScopeGuards.requirePlatformAdministrator(ctx))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void isTenantManager_allowsTenantAdminAndOwner() {
    AuthorizationContext admin = new AuthorizationContext();
    admin.setScopeType(AuthorizationScopeType.TENANT);
    admin.setActorRole(AuthorizationActorRole.TENANT_ADMIN);

    AuthorizationContext owner = new AuthorizationContext();
    owner.setUserId("owner1");
    owner.setScopeType(AuthorizationScopeType.TENANT);

    assertThat(AuthorizationScopeGuards.isTenantManager(admin, "other")).isTrue();
    assertThat(AuthorizationScopeGuards.isTenantManager(owner, "owner1")).isTrue();
  }

  @Test
  void isTenantManager_rejectsPersonalScope() {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setUserId("owner1");
    ctx.setScopeType(AuthorizationScopeType.PERSONAL);
    ctx.setActorRole(AuthorizationActorRole.PERSONAL_OWNER);

    assertThat(AuthorizationScopeGuards.isTenantManager(ctx, "owner1")).isFalse();
  }
}
