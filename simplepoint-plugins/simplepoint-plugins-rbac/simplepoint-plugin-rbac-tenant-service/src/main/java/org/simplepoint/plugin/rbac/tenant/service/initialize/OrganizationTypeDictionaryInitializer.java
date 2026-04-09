package org.simplepoint.plugin.rbac.tenant.service.initialize;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.simplepoint.api.data.DataInitRegister;
import org.simplepoint.api.data.InitTask;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.data.initialize.properties.DataInitializeProperties;
import org.simplepoint.data.initialize.properties.InitializerSettings;
import org.simplepoint.data.initialize.service.DataInitializeService;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Initializes the organization type dictionary and its default items on first startup.
 */
@Component
public class OrganizationTypeDictionaryInitializer {

  private static final String INIT_MODULE = "platform-organization-type-dictionary";
  private static final String I18N_MESSAGE_INIT_MODULE = "i18n-messages";

  /**
   * Registers the organization type dictionary initialization task.
   *
   * @param dictionaryService     the dictionary service
   * @param dictionaryItemService the dictionary item service
    * @param i18nMessageService    the i18n message service
   * @param dataInitializeProperties the initializer properties
   * @param dataInitializeService the data initializer service
   * @param serviceName the current service name
   * @return the data initialization register
   */
  @Bean
  public DataInitRegister organizationTypeDictionaryDataInitRegister(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService,
      final I18nMessageService i18nMessageService,
      final DataInitializeProperties dataInitializeProperties,
      final DataInitializeService dataInitializeService,
      @Value("${spring.application.name}") final String serviceName
  ) {
    return () -> new InitTask(
        INIT_MODULE,
        () -> initializeOrganizationTypeDictionary(
            dictionaryService,
            dictionaryItemService,
            i18nMessageService,
            dataInitializeProperties,
            dataInitializeService,
            serviceName
        )
    );
  }

  private void initializeOrganizationTypeDictionary(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService,
      final I18nMessageService i18nMessageService,
      final DataInitializeProperties dataInitializeProperties,
      final DataInitializeService dataInitializeService,
      final String serviceName
  ) {
    if (shouldInitializeMessages(serviceName, dataInitializeProperties, dataInitializeService)) {
      ensureMessage(i18nMessageService, "zh-CN", "organizations.title.type", "类型");
      ensureMessage(i18nMessageService, "zh-CN", "organizations.description.type", "组织机构的层级类型，从数据字典中选择");
      ensureMessage(i18nMessageService, "zh-CN", "organizations.type.group", "集团");
      ensureMessage(i18nMessageService, "zh-CN", "organizations.type.unit", "单位");
      ensureMessage(i18nMessageService, "zh-CN", "organizations.type.department", "部门");
      ensureMessage(i18nMessageService, "zh-CN", "organizations.type.team", "小组");
      ensureMessage(i18nMessageService, "zh-CN", "dictionaries.title.i18nKey", "国际化键");
      ensureMessage(
          i18nMessageService,
          "zh-CN",
          "dictionaries.description.i18nKey",
          "字典项名称对应的国际化消息键，选项展示时会优先使用该键"
      );
      ensureMessage(i18nMessageService, "en-US", "organizations.title.type", "Type");
      ensureMessage(
          i18nMessageService,
          "en-US",
          "organizations.description.type",
          "Hierarchy type of the organization, selected from the data dictionary"
      );
      ensureMessage(i18nMessageService, "en-US", "organizations.type.group", "Group");
      ensureMessage(i18nMessageService, "en-US", "organizations.type.unit", "Unit");
      ensureMessage(i18nMessageService, "en-US", "organizations.type.department", "Department");
      ensureMessage(i18nMessageService, "en-US", "organizations.type.team", "Team");
      ensureMessage(i18nMessageService, "en-US", "dictionaries.title.i18nKey", "I18n Key");
      ensureMessage(
          i18nMessageService,
          "en-US",
          "dictionaries.description.i18nKey",
          "I18n message key for the item label; option rendering prefers this key when present"
      );
    }
    Dictionary dictionary = ensureDictionary(dictionaryService);
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "group",
        item -> {
          item.setName("集团");
          item.setI18nKey("organizations.type.group");
          item.setDescription("用于表示集团级组织");
          item.setSort(10);
        }
    );
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "unit",
        item -> {
          item.setName("单位");
          item.setI18nKey("organizations.type.unit");
          item.setDescription("用于表示单位级组织");
          item.setSort(20);
        }
    );
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "department",
        item -> {
          item.setName("部门");
          item.setI18nKey("organizations.type.department");
          item.setDescription("用于表示部门级组织");
          item.setSort(30);
        }
    );
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "team",
        item -> {
          item.setName("小组");
          item.setI18nKey("organizations.type.team");
          item.setDescription("用于表示小组级组织");
          item.setSort(40);
        }
    );
  }

  private Dictionary ensureDictionary(DictionaryService dictionaryService) {
    return dictionaryService.findAll(Map.of("code", TenantDictionaryCodes.ORGANIZATION_TYPE))
        .stream()
        .findFirst()
        .map(existing -> {
          existing.setName("组织类型");
          existing.setDescription("用于定义组织机构的层级类型");
          existing.setSort(30);
          existing.setEnabled(Boolean.TRUE);
          return (Dictionary) dictionaryService.modifyById(existing);
        })
        .orElseGet(() -> {
          Dictionary dictionary = new Dictionary();
          dictionary.setName("组织类型");
          dictionary.setCode(TenantDictionaryCodes.ORGANIZATION_TYPE);
          dictionary.setDescription("用于定义组织机构的层级类型");
          dictionary.setSort(30);
          dictionary.setEnabled(Boolean.TRUE);
          return dictionaryService.create(dictionary);
        });
  }

  private void ensureDictionaryItem(
      DictionaryItemService dictionaryItemService,
      String dictionaryCode,
      String value,
      Consumer<DictionaryItem> customizer
  ) {
    DictionaryItem current = dictionaryItemService.findAll(Map.of("dictionaryCode", dictionaryCode))
        .stream()
        .filter(item -> Objects.equals(item.getValue(), value))
        .findFirst()
        .orElse(null);
    if (current == null) {
      DictionaryItem item = new DictionaryItem();
      item.setDictionaryCode(dictionaryCode);
      item.setValue(value);
      customizer.accept(item);
      item.setEnabled(Boolean.TRUE);
      dictionaryItemService.create(item);
      return;
    }
    customizer.accept(current);
    current.setDictionaryCode(dictionaryCode);
    current.setValue(value);
    current.setEnabled(Boolean.TRUE);
    dictionaryItemService.modifyById(current);
  }

  static boolean shouldInitializeMessages(
      final String serviceName,
      final DataInitializeProperties dataInitializeProperties,
      final DataInitializeService dataInitializeService
  ) {
    if (dataInitializeProperties == null) {
      return true;
    }
    Map<String, InitializerSettings> modules = dataInitializeProperties.getModule();
    if (modules == null) {
      return true;
    }
    InitializerSettings settings = modules.get(I18N_MESSAGE_INIT_MODULE);
    if (settings == null || !settings.enabled()) {
      return true;
    }
    if (serviceName == null || serviceName.isBlank() || dataInitializeService == null) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(dataInitializeService.isDone(serviceName, I18N_MESSAGE_INIT_MODULE));
    } catch (Exception ex) {
      return false;
    }
  }

  static String resolveNamespace(final String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("i18n code must not be blank");
    }
    int separator = code.indexOf('.');
    if (separator <= 0) {
      throw new IllegalArgumentException("Unable to resolve namespace for i18n code: " + code);
    }
    return code.substring(0, separator);
  }

  private void ensureMessage(
      final I18nMessageService i18nMessageService,
      final String locale,
      final String code,
      final String value
  ) {
    String namespace = resolveNamespace(code);
    Message current = i18nMessageService.findAll(Map.of("namespace", namespace, "code", code, "locale", locale))
        .stream()
        .findFirst()
        .orElse(null);
    if (current == null) {
      Message message = new Message();
      message.setNamespace(namespace);
      message.setCode(code);
      message.setLocale(locale);
      message.setMessage(value);
      message.setGlobal(Boolean.FALSE);
      i18nMessageService.create(message);
      return;
    }
    current.setMessage(value);
    current.setGlobal(Boolean.FALSE);
    i18nMessageService.modifyById(current);
  }
}
