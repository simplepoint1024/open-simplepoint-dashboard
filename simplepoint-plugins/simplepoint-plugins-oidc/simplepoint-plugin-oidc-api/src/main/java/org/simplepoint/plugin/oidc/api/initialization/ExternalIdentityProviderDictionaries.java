package org.simplepoint.plugin.oidc.api.initialization;

import java.util.List;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryDefinitions;
import org.simplepoint.platform.dictionary.DictionaryItemDeclaration;
import org.simplepoint.plugin.oidc.api.constants.ExternalIdentityProviderDictionaryCodes;

/** Authoritative domain dictionary metadata; independent of identity persistence and hosting. */
public final class ExternalIdentityProviderDictionaries implements DictionaryDefinitions {
  private static final DictionaryContribution DECLARATION =
      new DictionaryContribution(
          "oidc",
          "external-identity-provider-dictionaries",
          "1",
          320,
          List.of(
              new DictionaryDeclaration(
                  ExternalIdentityProviderDictionaryCodes.PRESET,
                  "身份提供商模板",
                  "常用外部身份平台的标准配置模板",
                  70,
                  List.of(
                      new DictionaryItemDeclaration(
                          "CUSTOM", "自定义", "external-idp.option.preset.CUSTOM", "身份提供商模板：自定义", 10),
                      new DictionaryItemDeclaration(
                          "GOOGLE",
                          "Google",
                          "external-idp.option.preset.GOOGLE",
                          "身份提供商模板：Google",
                          20),
                      new DictionaryItemDeclaration(
                          "GITHUB",
                          "GitHub",
                          "external-idp.option.preset.GITHUB",
                          "身份提供商模板：GitHub",
                          30),
                      new DictionaryItemDeclaration(
                          "APPLE",
                          "Apple",
                          "external-idp.option.preset.APPLE",
                          "身份提供商模板：Apple",
                          40),
                      new DictionaryItemDeclaration(
                          "MICROSOFT",
                          "Microsoft",
                          "external-idp.option.preset.MICROSOFT",
                          "身份提供商模板：Microsoft",
                          50))),
              new DictionaryDeclaration(
                  ExternalIdentityProviderDictionaryCodes.PROTOCOL,
                  "身份协议",
                  "外部身份提供商采用的标准协议",
                  71,
                  List.of(
                      new DictionaryItemDeclaration(
                          "OIDC",
                          "OpenID Connect",
                          "external-idp.option.protocol.OIDC",
                          "身份协议：OpenID Connect",
                          10),
                      new DictionaryItemDeclaration(
                          "OAUTH2",
                          "OAuth 2.0",
                          "external-idp.option.protocol.OAUTH2",
                          "身份协议：OAuth 2.0",
                          20))),
              new DictionaryDeclaration(
                  ExternalIdentityProviderDictionaryCodes.CLIENT_AUTHENTICATION_METHOD,
                  "客户端认证方式",
                  "调用身份提供商 Token Endpoint 时的客户端认证方式",
                  72,
                  List.of(
                      new DictionaryItemDeclaration(
                          "client_secret_basic",
                          "Client Secret Basic",
                          "external-idp.option.clientAuthenticationMethod.client_secret_basic",
                          "客户端认证方式：Client Secret Basic",
                          10),
                      new DictionaryItemDeclaration(
                          "client_secret_post",
                          "Client Secret Post",
                          "external-idp.option.clientAuthenticationMethod.client_secret_post",
                          "客户端认证方式：Client Secret Post",
                          20),
                      new DictionaryItemDeclaration(
                          "none",
                          "公开客户端（无密钥）",
                          "external-idp.option.clientAuthenticationMethod.none",
                          "客户端认证方式：公开客户端（无密钥）",
                          30))),
              new DictionaryDeclaration(
                  ExternalIdentityProviderDictionaryCodes.MATCH_STRATEGY,
                  "账号匹配策略",
                  "首次外部登录时绑定本地账号的安全策略",
                  73,
                  List.of(
                      new DictionaryItemDeclaration(
                          "VERIFIED_EMAIL",
                          "已验证邮箱",
                          "external-idp.option.matchStrategy.VERIFIED_EMAIL",
                          "账号匹配策略：已验证邮箱",
                          10),
                      new DictionaryItemDeclaration(
                          "USER_ID",
                          "本地用户 ID",
                          "external-idp.option.matchStrategy.USER_ID",
                          "账号匹配策略：本地用户 ID",
                          20)))));

  /** Public metadata constructor for module planning. */
  public ExternalIdentityProviderDictionaries() {}

  @Override
  public DictionaryContribution declaration() {
    return DECLARATION;
  }
}
