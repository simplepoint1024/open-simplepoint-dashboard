package org.simplepoint.plugin.rbac.tenant.service.initialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.data.initialize.properties.DataInitializeProperties;
import org.simplepoint.data.initialize.properties.InitializerSettings;
import org.simplepoint.data.initialize.service.DataInitializeService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;

class OrganizationTypeDictionaryInitializerTest {

  // ── shouldInitializeMessages static method ─────────────────────────────────

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
  void shouldInitializeMessages_nullProperties_returnsTrue() {
    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common", null, new StubDataInitializeService(false));
    assertTrue(result);
  }

  @Test
  void shouldInitializeMessages_nullModules_returnsTrue() {
    DataInitializeProperties properties = new DataInitializeProperties();
    // module map is null by default
    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common", properties, new StubDataInitializeService(false));
    assertTrue(result);
  }

  @Test
  void shouldInitializeMessages_missingModuleKey_returnsTrue() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("other-module", new InitializerSettings(true)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common", properties, new StubDataInitializeService(false));
    assertTrue(result);
  }

  @Test
  void shouldInitializeMessages_nullServiceName_returnsFalse() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        null, properties, new StubDataInitializeService(true));
    assertFalse(result);
  }

  @Test
  void shouldInitializeMessages_blankServiceName_returnsFalse() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "   ", properties, new StubDataInitializeService(true));
    assertFalse(result);
  }

  @Test
  void shouldInitializeMessages_nullDataInitializeService_returnsFalse() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common", properties, null);
    assertFalse(result);
  }

  @Test
  void shouldInitializeMessages_isDoneThrowsException_returnsFalse() {
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));

    DataInitializeService throwingService = mock(DataInitializeService.class);
    when(throwingService.isDone("common", "i18n-messages"))
        .thenThrow(new RuntimeException("connection error"));

    boolean result = OrganizationTypeDictionaryInitializer.shouldInitializeMessages(
        "common", properties, throwingService);
    assertFalse(result);
  }

  // ── resolveNamespace static method ─────────────────────────────────────────

  @Test
  void shouldResolveNamespaceFromMessageCode() {
    assertEquals("organizations", OrganizationTypeDictionaryInitializer.resolveNamespace("organizations.description.type"));
    assertEquals("dictionaries", OrganizationTypeDictionaryInitializer.resolveNamespace("dictionaries.title.i18nKey"));
  }

  @Test
  void shouldRejectInvalidMessageCodeWhenResolvingNamespace() {
    assertThrows(IllegalArgumentException.class, () -> OrganizationTypeDictionaryInitializer.resolveNamespace("invalid"));
  }

  @Test
  void resolveNamespace_leadingDot_throwsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> OrganizationTypeDictionaryInitializer.resolveNamespace(".leading.dot"));
  }

  @Test
  void resolveNamespace_nullCode_throwsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> OrganizationTypeDictionaryInitializer.resolveNamespace(null));
  }

  @Test
  void resolveNamespace_blankCode_throwsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> OrganizationTypeDictionaryInitializer.resolveNamespace("  "));
  }

  // ── bean init logic ─────────────────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void initTask_noExistingData_createsAllDictionaryItemsAndMessages() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    I18nMessageService i18nMessageService = mock(I18nMessageService.class);

    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("organization.type");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(i18nMessageService.findAll(any(Map.class))).thenReturn(List.of());
    when(i18nMessageService.create(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    // null properties → shouldInitializeMessages returns true → messages created
    var register = initializer.organizationTypeDictionaryDataInitRegister(
        dictionaryService, dictionaryItemService, i18nMessageService, null, null, "common");

    register.register().task().run();

    verify(dictionaryService).create(any(Dictionary.class));
    verify(dictionaryItemService, times(4)).create(any(DictionaryItem.class));
    verify(i18nMessageService, times(16)).create(any(Message.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void initTask_existingDictionary_modifiesInsteadOfCreate() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    I18nMessageService i18nMessageService = mock(I18nMessageService.class);

    Dictionary existingDictionary = new Dictionary();
    existingDictionary.setCode("organization.type");
    existingDictionary.setName("Old Name");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of(existingDictionary));
    when(dictionaryService.modifyById(any(Dictionary.class))).thenReturn(existingDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(i18nMessageService.findAll(any(Map.class))).thenReturn(List.of());
    when(i18nMessageService.create(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    var register = initializer.organizationTypeDictionaryDataInitRegister(
        dictionaryService, dictionaryItemService, i18nMessageService, null, null, "common");

    register.register().task().run();

    verify(dictionaryService).modifyById(existingDictionary);
  }

  @Test
  @SuppressWarnings("unchecked")
  void initTask_existingDictionaryItem_modifiesInsteadOfCreate() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    I18nMessageService i18nMessageService = mock(I18nMessageService.class);

    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("organization.type");

    DictionaryItem existingGroup = new DictionaryItem();
    existingGroup.setValue("group");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of(existingGroup));
    when(dictionaryItemService.modifyById(any(DictionaryItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(i18nMessageService.findAll(any(Map.class))).thenReturn(List.of());
    when(i18nMessageService.create(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    var register = initializer.organizationTypeDictionaryDataInitRegister(
        dictionaryService, dictionaryItemService, i18nMessageService, null, null, "common");

    register.register().task().run();

    verify(dictionaryItemService, atLeastOnce()).modifyById(any(DictionaryItem.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void initTask_existingMessage_modifiesInsteadOfCreate() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    I18nMessageService i18nMessageService = mock(I18nMessageService.class);

    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("organization.type");

    Message existingMsg = new Message();
    existingMsg.setCode("organizations.title.type");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(i18nMessageService.findAll(any(Map.class))).thenReturn(List.of(existingMsg));
    when(i18nMessageService.modifyById(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    var register = initializer.organizationTypeDictionaryDataInitRegister(
        dictionaryService, dictionaryItemService, i18nMessageService, null, null, "common");

    register.register().task().run();

    verify(i18nMessageService, atLeastOnce()).modifyById(any(Message.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void initTask_messagesDisabledModule_skipsMessageInit() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    I18nMessageService i18nMessageService = mock(I18nMessageService.class);

    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("organization.type");

    // Set up properties: i18n-messages enabled and isDone=false → skip messages
    DataInitializeProperties properties = new DataInitializeProperties();
    properties.setModule(Map.of("i18n-messages", new InitializerSettings(true)));
    DataInitializeService dataInitService = new StubDataInitializeService(false);

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    var register = initializer.organizationTypeDictionaryDataInitRegister(
        dictionaryService, dictionaryItemService, i18nMessageService, properties, dataInitService, "common");

    register.register().task().run();

    // No message service calls since shouldInitializeMessages returned false
    verify(i18nMessageService, times(0)).create(any(Message.class));
    verify(i18nMessageService, times(0)).findAll(any(Map.class));
  }

  // ── stub ───────────────────────────────────────────────────────────────────

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
