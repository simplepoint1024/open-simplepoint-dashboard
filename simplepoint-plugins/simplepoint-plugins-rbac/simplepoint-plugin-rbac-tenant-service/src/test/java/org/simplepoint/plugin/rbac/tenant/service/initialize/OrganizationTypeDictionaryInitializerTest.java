package org.simplepoint.plugin.rbac.tenant.service.initialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.initialize.properties.DataInitializeProperties;
import org.simplepoint.data.initialize.properties.InitializerSettings;
import org.simplepoint.data.initialize.service.DataInitializeService;

class OrganizationTypeDictionaryInitializerTest {

  @Test
  void shouldSkipManualMessageSeedWhenI18nMessagesStillPending() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common",
        properties,
        new StubDataInitializeService(false)
    );

    assertFalse(result);
  }

  @Test
  void shouldAllowManualMessageSeedWhenI18nMessagesAlreadyDone() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common",
        properties,
        new StubDataInitializeService(true)
    );

    assertTrue(result);
  }

  @Test
  void shouldAllowManualMessageSeedWhenI18nMessagesModuleDisabled() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(false)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common",
        properties,
        new StubDataInitializeService(false)
    );

    assertTrue(result);
  }

  @Test
  void shouldResolveNamespaceFromMessageCode() {
    assertEquals("organizations", OrganizationTypeDictionaryInitializer.resolveNamespace("organizations.description.type"));
    assertEquals("dictionaries", OrganizationTypeDictionaryInitializer.resolveNamespace("dictionaries.title.i18nKey"));
  }

  @Test
  void shouldRejectInvalidMessageCodeWhenResolvingNamespace() {
    assertThrows(IllegalArgumentException.class, () -> OrganizationTypeDictionaryInitializer.resolveNamespace("invalid"));
  }

  private static final class StubDataInitializeService implements DataInitializeService {
    private final Boolean done;

    private StubDataInitializeService(final Boolean done) {
      this.done = done;
    }

    @Override
    public Boolean start(final String serviceName, final String moduleName) {
      return Boolean.TRUE;
    }

    @Override
    public Boolean done(final String serviceName, final String moduleName) {
      return Boolean.TRUE;
    }

    @Override
    public void fail(final String serviceName, final String moduleName, final String error) {
    }

    @Override
    public Boolean isDone(final String serviceName, final String moduleName) {
      return done;
    }
  }
}
