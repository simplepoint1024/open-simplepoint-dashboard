package org.simplepoint.plugin.ai.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;

class AiButtonAuthorityTest {

  @Test
  void providerActionsUseUnifiedWorkbenchAuthorities() {
    assertEquals(
        Set.of("create", "edit", "delete", "test", "discover", "sync"),
        operations(AiProviderDefinition.class)
    );
    assertUnifiedAuthorities(AiProviderDefinition.class);
  }

  @Test
  void modelActionsUseUnifiedWorkbenchAuthorities() {
    assertEquals(Set.of("create", "edit", "delete", "debug"), operations(AiModelDefinition.class));
    assertUnifiedAuthorities(AiModelDefinition.class);
  }

  private static Set<String> operations(final Class<?> entityType) {
    ButtonDeclarations declarations = entityType.getAnnotation(ButtonDeclarations.class);
    return Arrays.stream(declarations.value())
        .map(AiButtonAuthorityTest::operation)
        .collect(Collectors.toSet());
  }

  private static void assertUnifiedAuthorities(final Class<?> entityType) {
    ButtonDeclarations declarations = entityType.getAnnotation(ButtonDeclarations.class);
    Set<String> prefixes = Arrays.stream(declarations.value())
        .map(ButtonDeclaration::authority)
        .map(authority -> authority.substring(0, authority.lastIndexOf('.')))
        .collect(Collectors.toSet());
    assertEquals(Set.of(
        entityType == AiProviderDefinition.class
            ? "ai.workbench.providers" : "ai.workbench.models"
    ), prefixes);
  }

  private static String operation(final ButtonDeclaration declaration) {
    String authority = declaration.authority();
    return authority.substring(authority.lastIndexOf('.') + 1);
  }
}
