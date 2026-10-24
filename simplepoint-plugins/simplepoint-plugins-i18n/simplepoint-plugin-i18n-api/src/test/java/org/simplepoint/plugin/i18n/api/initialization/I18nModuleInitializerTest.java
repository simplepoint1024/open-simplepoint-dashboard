package org.simplepoint.plugin.i18n.api.initialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;

class I18nModuleInitializerTest {
  private final I18nMessageRegistrationService service = mock(I18nMessageRegistrationService.class);

  @Test
  void preparesWithoutRemoteEffectsThenRegistersOnlyMissingMessages() throws Exception {
    when(service.findExistingNamespaceCodes()).thenReturn(Set.of("example"));
    when(service.findExistingMessageKeys(Set.of("example")))
        .thenReturn(Set.of("en-US:example:old"));
    var action =
        new I18nModuleInitializer()
            .prepare(
                context(
                    "en-US/example.json",
                    "{\"old\":\"do not replace admin value\",\"new\":\"new value\"}"));
    verifyNoInteractions(service);
    action.run();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<MessageDeclaration>> messages = ArgumentCaptor.forClass(Set.class);
    verify(service).register(anySet(), messages.capture());
    assertThat(messages.getValue())
        .containsExactly(
            new MessageDeclaration("en-US", "example", "new", "new value", null, false));
  }

  @Test
  void rejectsDuplicateKeysMalformedValueAndTrailingJsonBeforeRemoteEffects() {
    for (String json :
        new String[] {
          "{\"key\":\"one\",\"key\":\"two\"}", "{\"key\":3}", "{\"key\":null}", "{} {}", "[]"
        }) {
      assertThatThrownBy(
              () -> new I18nModuleInitializer().prepare(context("en-US/example.json", json)))
          .isInstanceOf(Exception.class);
    }
    verifyNoInteractions(service);
  }

  @Test
  void rejectsMissingEmptyDuplicateOrTraversingIndexEntries() {
    for (String index :
        new String[] {
          "", "../other.json", "en-US/example.json\nen-US/example.json", "en-US/missing.json"
        }) {
      assertThatThrownBy(() -> new I18nModuleInitializer().prepare(context(index, "{}")))
          .isInstanceOf(Exception.class);
    }
    verifyNoInteractions(service);
  }

  private ModuleInitializationContext context(String index, String json) {
    return new ModuleInitializationContext() {
      @Override
      public String moduleId() {
        return "example";
      }

      @Override
      public <T> T client(Class<T> contract) {
        return contract.cast(service);
      }

      @Override
      public byte[] resource(String path) {
        return Map.of("i18n.imports", index, "i18n/en-US/example.json", json)
            .get(path)
            .getBytes(StandardCharsets.UTF_8);
      }
    };
  }
}
