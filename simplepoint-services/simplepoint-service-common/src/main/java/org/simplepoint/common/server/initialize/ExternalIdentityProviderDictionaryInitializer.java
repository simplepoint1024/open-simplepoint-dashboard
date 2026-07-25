package org.simplepoint.common.server.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.oidc.api.constants.ExternalIdentityProviderDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.service.initialize.BuiltInDictionaryRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Registers dictionaries used by external identity-provider schemas. */
@Component
public class ExternalIdentityProviderDictionaryInitializer {

  private static final String INIT_MODULE = "external-identity-provider-dictionaries";

  /**
   * Registers identity-provider dictionaries as an idempotent platform contribution.
   */
  @Bean
  public PlatformBootstrapContribution externalIdentityProviderDictionaryContribution(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    return () -> BootstrapContribution.versioned(
        "oidc",
        "dictionary",
        INIT_MODULE,
        "1",
        320,
        () -> initializeDictionaries(dictionaryService, dictionaryItemService)
    );
  }

  private void initializeDictionaries(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    Dictionary preset = dictionary(
        dictionaryService,
        ExternalIdentityProviderDictionaryCodes.PRESET,
        "身份提供商模板",
        "常用外部身份平台的标准配置模板",
        70
    );
    item(dictionaryItemService, preset, "CUSTOM", "自定义", "preset", 10);
    item(dictionaryItemService, preset, "GOOGLE", "Google", "preset", 20);
    item(dictionaryItemService, preset, "GITHUB", "GitHub", "preset", 30);
    item(dictionaryItemService, preset, "APPLE", "Apple", "preset", 40);
    item(dictionaryItemService, preset, "MICROSOFT", "Microsoft", "preset", 50);

    Dictionary protocol = dictionary(
        dictionaryService,
        ExternalIdentityProviderDictionaryCodes.PROTOCOL,
        "身份协议",
        "外部身份提供商采用的标准协议",
        71
    );
    item(dictionaryItemService, protocol, "OIDC", "OpenID Connect", "protocol", 10);
    item(dictionaryItemService, protocol, "OAUTH2", "OAuth 2.0", "protocol", 20);

    Dictionary authenticationMethod = dictionary(
        dictionaryService,
        ExternalIdentityProviderDictionaryCodes.CLIENT_AUTHENTICATION_METHOD,
        "客户端认证方式",
        "调用身份提供商 Token Endpoint 时的客户端认证方式",
        72
    );
    item(dictionaryItemService, authenticationMethod, "client_secret_basic",
        "Client Secret Basic", "clientAuthenticationMethod", 10);
    item(dictionaryItemService, authenticationMethod, "client_secret_post",
        "Client Secret Post", "clientAuthenticationMethod", 20);
    item(dictionaryItemService, authenticationMethod, "none",
        "公开客户端（无密钥）", "clientAuthenticationMethod", 30);

    Dictionary matchStrategy = dictionary(
        dictionaryService,
        ExternalIdentityProviderDictionaryCodes.MATCH_STRATEGY,
        "账号匹配策略",
        "首次外部登录时绑定本地账号的安全策略",
        73
    );
    item(dictionaryItemService, matchStrategy, "VERIFIED_EMAIL",
        "已验证邮箱", "matchStrategy", 10);
    item(dictionaryItemService, matchStrategy, "USER_ID",
        "本地用户 ID", "matchStrategy", 20);
  }

  private static Dictionary dictionary(
      final DictionaryService dictionaryService,
      final String code,
      final String name,
      final String description,
      final int sort
  ) {
    return BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        code,
        name,
        description,
        sort
    );
  }

  private static void item(
      final DictionaryItemService itemService,
      final Dictionary dictionary,
      final String value,
      final String name,
      final String group,
      final int sort
  ) {
    BuiltInDictionaryRegistrar.ensureItem(
        itemService,
        dictionary.getCode(),
        value,
        name,
        "external-idp.option." + group + "." + value,
        dictionary.getName() + "：" + name,
        sort
    );
  }
}
