package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.jackson.JacksonAutoConfiguration;
import org.simplepoint.security.context.AuthorizationContextService;

/** Checks the actual RPC codec and the application JSON mapper used by the Redis cache. */
class AuthorizationContextWireTest {
  @Test
  void rpcAndCacheRoundTripRetainExclusiveDeadlineAndTenantScope() throws Exception {
    var mapper = new JacksonAutoConfiguration().objectMapper();
    var codec = new ServiceRouterJsonCodec(mapper);
    var context = new AuthorizationContext();
    context.setUserId("subject");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setVersion(7L);
    context.setResources(Set.of("paid.page"));
    context.setValidUntil(Instant.parse("2026-10-23T06:00:00.123456Z"));
    var envelope = codec.decodeResponse(codec.encode(RemoteResponse.success(context)));
    var operation =
        AuthorizationContextService.class.getMethod(
            "checkedCalculate", String.class, String.class, String.class, Map.class);
    var routed =
        (AuthorizationContext)
            codec.result(AuthorizationContextService.class, operation, envelope.data());
    var cached = mapper.readValue(mapper.writeValueAsBytes(routed), AuthorizationContext.class);
    assertThat(cached.getValidUntil()).isEqualTo(context.getValidUntil());
    assertThat(cached.getUserId()).isEqualTo("subject");
    assertThat(cached.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
    assertThat(cached.getAttribute("X-Tenant-Id")).isEqualTo("tenant");
    assertThat(cached.getVersion()).isEqualTo(7L);
    assertThat(cached.getResources()).containsExactly("paid.page");
  }

  @Test
  void deadlineCannotBeExtendedByCallingItsSetterAgain() {
    var context = new AuthorizationContext();
    var deadline = Instant.parse("2026-10-23T06:00:00Z");
    context.setValidUntil(deadline);
    context.setValidUntil(deadline.plusSeconds(30));
    context.setValidUntil(null);
    assertThat(context.getValidUntil()).isEqualTo(deadline);
  }
}
