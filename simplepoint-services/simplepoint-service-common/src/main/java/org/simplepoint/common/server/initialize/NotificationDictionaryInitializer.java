package org.simplepoint.common.server.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.notification.api.constants.NotificationDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.service.initialize.BuiltInDictionaryRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Registers internationalized dictionaries used by system notification schemas. */
@Component
public class NotificationDictionaryInitializer {

  private static final String INIT_MODULE = "system-notification-dictionaries";

  /**
   * Registers notification dictionaries as an idempotent platform contribution.
   *
   * @param dictionaryService     dictionary management service
   * @param dictionaryItemService dictionary item management service
   * @return the platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution notificationDictionaryBootstrapContribution(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    return () -> BootstrapContribution.versioned(
        "notification",
        "dictionary",
        INIT_MODULE,
        "1",
        310,
        () -> initializeDictionaries(dictionaryService, dictionaryItemService)
    );
  }

  private void initializeDictionaries(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    Dictionary category = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        NotificationDictionaryCodes.CATEGORY,
        "通知分类",
        "系统通知的业务内容分类",
        60
    );
    ensureItem(dictionaryItemService, category, "ANNOUNCEMENT", "公告", "category", 10);
    ensureItem(dictionaryItemService, category, "SYSTEM", "系统", "category", 20);
    ensureItem(dictionaryItemService, category, "MAINTENANCE", "维护", "category", 30);
    ensureItem(dictionaryItemService, category, "SECURITY", "安全", "category", 40);

    Dictionary priority = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        NotificationDictionaryCodes.PRIORITY,
        "通知优先级",
        "系统通知的展示与处理优先级",
        61
    );
    ensureItem(dictionaryItemService, priority, "LOW", "低", "priority", 10);
    ensureItem(dictionaryItemService, priority, "NORMAL", "普通", "priority", 20);
    ensureItem(dictionaryItemService, priority, "HIGH", "高", "priority", 30);
    ensureItem(dictionaryItemService, priority, "URGENT", "紧急", "priority", 40);

    Dictionary audience = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        NotificationDictionaryCodes.AUDIENCE_TYPE,
        "通知目标类型",
        "系统通知的目标用户选择方式",
        62
    );
    ensureItem(dictionaryItemService, audience, "ALL", "全部用户", "audience", 10);
    ensureItem(dictionaryItemService, audience, "PLATFORM", "平台工作区", "audience", 20);
    ensureItem(dictionaryItemService, audience, "TENANT", "指定租户", "audience", 30);
    ensureItem(dictionaryItemService, audience, "USER", "指定用户", "audience", 40);

    Dictionary status = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        NotificationDictionaryCodes.STATUS,
        "通知状态",
        "系统通知的生命周期状态",
        63
    );
    ensureItem(dictionaryItemService, status, "DRAFT", "草稿", "status", 10);
    ensureItem(dictionaryItemService, status, "PUBLISHED", "已发布", "status", 20);
    ensureItem(dictionaryItemService, status, "REVOKED", "已撤回", "status", 30);
  }

  private static void ensureItem(
      final DictionaryItemService dictionaryItemService,
      final Dictionary dictionary,
      final String value,
      final String name,
      final String i18nGroup,
      final int sort
  ) {
    BuiltInDictionaryRegistrar.ensureItem(
        dictionaryItemService,
        dictionary.getCode(),
        value,
        name,
        "notifications." + i18nGroup + "." + value,
        dictionary.getName() + "：" + name,
        sort
    );
  }
}
