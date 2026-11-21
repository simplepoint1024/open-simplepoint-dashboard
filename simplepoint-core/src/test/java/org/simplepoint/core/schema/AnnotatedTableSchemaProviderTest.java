package org.simplepoint.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;

class AnnotatedTableSchemaProviderTest {
  private final AnnotatedTableSchemaProvider schemas = new AnnotatedTableSchemaProvider(null);

  @Test
  void tenantButtonRequiresEffectiveGrantEvenForPlatformAdministrators() {
    assertThat(schemas.buttonSchemas(Row.class, context(AuthorizationScopeType.TENANT, Set.of())))
        .isEmpty();
    assertThat(schemas.buttonSchemas(Row.class,
        context(AuthorizationScopeType.TENANT, Set.of("example.records.delete")))).hasSize(1);
    assertThat(schemas.buttonSchemas(Row.class, context(AuthorizationScopeType.PLATFORM, Set.of())))
        .hasSize(1);
  }

  private static AuthorizationContext context(AuthorizationScopeType scope, Set<String> grants) {
    var context = new AuthorizationContext();
    context.setScopeType(scope);
    context.setIsAdministrator(true);
    context.setResources(grants);
    return context;
  }

  @ButtonDeclarations(@ButtonDeclaration(key = "delete", authority = "example.records.delete"))
  private static class Row {}
}
