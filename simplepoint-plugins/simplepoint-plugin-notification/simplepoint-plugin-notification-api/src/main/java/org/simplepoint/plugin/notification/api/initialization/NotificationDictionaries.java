package org.simplepoint.plugin.notification.api.initialization;

import java.util.List;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryDefinitions;
import org.simplepoint.platform.dictionary.DictionaryItemDeclaration;
import org.simplepoint.plugin.notification.api.constants.NotificationDictionaryCodes;

/** Authoritative domain dictionary metadata; independent of identity persistence and hosting. */
public final class NotificationDictionaries implements DictionaryDefinitions {
  private static final DictionaryContribution DECLARATION =
      new DictionaryContribution(
          "notification",
          "system-notification-dictionaries",
          "1",
          310,
          List.of(
              new DictionaryDeclaration(
                  NotificationDictionaryCodes.CATEGORY,
                  "通知分类",
                  "系统通知的业务内容分类",
                  60,
                  List.of(
                      new DictionaryItemDeclaration(
                          "ANNOUNCEMENT",
                          "公告",
                          "notifications.category.ANNOUNCEMENT",
                          "通知分类：公告",
                          10),
                      new DictionaryItemDeclaration(
                          "SYSTEM", "系统", "notifications.category.SYSTEM", "通知分类：系统", 20),
                      new DictionaryItemDeclaration(
                          "MAINTENANCE", "维护", "notifications.category.MAINTENANCE", "通知分类：维护", 30),
                      new DictionaryItemDeclaration(
                          "SECURITY", "安全", "notifications.category.SECURITY", "通知分类：安全", 40))),
              new DictionaryDeclaration(
                  NotificationDictionaryCodes.PRIORITY,
                  "通知优先级",
                  "系统通知的展示与处理优先级",
                  61,
                  List.of(
                      new DictionaryItemDeclaration(
                          "LOW", "低", "notifications.priority.LOW", "通知优先级：低", 10),
                      new DictionaryItemDeclaration(
                          "NORMAL", "普通", "notifications.priority.NORMAL", "通知优先级：普通", 20),
                      new DictionaryItemDeclaration(
                          "HIGH", "高", "notifications.priority.HIGH", "通知优先级：高", 30),
                      new DictionaryItemDeclaration(
                          "URGENT", "紧急", "notifications.priority.URGENT", "通知优先级：紧急", 40))),
              new DictionaryDeclaration(
                  NotificationDictionaryCodes.AUDIENCE_TYPE,
                  "通知目标类型",
                  "系统通知的目标用户选择方式",
                  62,
                  List.of(
                      new DictionaryItemDeclaration(
                          "ALL", "全部用户", "notifications.audience.ALL", "通知目标类型：全部用户", 10),
                      new DictionaryItemDeclaration(
                          "PLATFORM",
                          "平台工作区",
                          "notifications.audience.PLATFORM",
                          "通知目标类型：平台工作区",
                          20),
                      new DictionaryItemDeclaration(
                          "TENANT", "指定租户", "notifications.audience.TENANT", "通知目标类型：指定租户", 30),
                      new DictionaryItemDeclaration(
                          "USER", "指定用户", "notifications.audience.USER", "通知目标类型：指定用户", 40))),
              new DictionaryDeclaration(
                  NotificationDictionaryCodes.STATUS,
                  "通知状态",
                  "系统通知的生命周期状态",
                  63,
                  List.of(
                      new DictionaryItemDeclaration(
                          "DRAFT", "草稿", "notifications.status.DRAFT", "通知状态：草稿", 10),
                      new DictionaryItemDeclaration(
                          "PUBLISHED", "已发布", "notifications.status.PUBLISHED", "通知状态：已发布", 20),
                      new DictionaryItemDeclaration(
                          "REVOKED", "已撤回", "notifications.status.REVOKED", "通知状态：已撤回", 30)))));

  /** Public metadata constructor for module planning. */
  public NotificationDictionaries() {}

  @Override
  public DictionaryContribution declaration() {
    return DECLARATION;
  }
}
