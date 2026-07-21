package org.simplepoint.plugin.ai.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;

class AiButtonAuthorityTest {

  @Test
  void providerActionsAreEquivalentAcrossSystemAndTenantScopes() {
    Map<String, Set<String>> authorities = authoritiesByScope(AiProviderDefinition.class);

    assertEquals(
        Set.of("create", "edit", "delete", "test", "discover", "sync"),
        authorities.get("system")
    );
    assertEquals(authorities.get("system"), authorities.get("tenant"));
  }

  @Test
  void modelActionsAreEquivalentAcrossSystemAndTenantScopes() {
    Map<String, Set<String>> authorities = authoritiesByScope(AiModelDefinition.class);

    assertEquals(Set.of("create", "edit", "delete"), authorities.get("system"));
    assertEquals(authorities.get("system"), authorities.get("tenant"));
  }

  private static Map<String, Set<String>> authoritiesByScope(final Class<?> entityType) {
    ButtonDeclarations declarations = entityType.getAnnotation(ButtonDeclarations.class);
    return Arrays.stream(declarations.value()).collect(Collectors.groupingBy(
        declaration -> declaration.authority().startsWith("ai.system.") ? "system" : "tenant",
        Collectors.mapping(AiButtonAuthorityTest::operation, Collectors.toSet())
    ));
  }

  private static String operation(final ButtonDeclaration declaration) {
    String authority = declaration.authority();
    return authority.substring(authority.lastIndexOf('.') + 1);
  }
}
