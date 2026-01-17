package org.simplepoint.cloud.oauth.server.configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.vault.core.VaultKeyValueOperationsSupport;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

/**
 * Configuration class for JSON Web Key (JWK) management using Vault.
 * 使用 Vault 管理 JSON Web Key (JWK) 的配置类
 */
@Configuration
public class JwkConfiguration {

  private static final String VAULT_JWK_PATH = "secret/data/sas/jwk";
  private static final String JWK_FIELD = "jwk";

  private final VaultTemplate vaultTemplate;

  /**
   * Constructor for JwkConfiguration.
   *
   * @param vaultTemplate the VaultTemplate to interact with Vault
   */
  public JwkConfiguration(VaultTemplate vaultTemplate) {
    this.vaultTemplate = vaultTemplate;
  }

  /**
   * Bean for JWKSource that loads JWKs from Vault.
   *
   * @return JWKSource for JWT processing
   */
  @Bean
  @ConditionalOnMissingBean
  public JWKSource<SecurityContext> jwkSource() {
    // 从 Vault 读取 JWK JSON
    VaultResponse response = vaultTemplate.opsForKeyValue("secret", VaultKeyValueOperationsSupport.KeyValueBackend.KV_2).get("sas/jwk");
    if (response == null || response.getData() == null) {
      throw new IllegalStateException("Cannot load JWK from Vault path: " + VAULT_JWK_PATH);
    }

    String jwkJson = (String) response.getData().get(JWK_FIELD);
    if (jwkJson == null || jwkJson.isBlank()) {
      throw new IllegalStateException("Vault path " + VAULT_JWK_PATH + " does not contain field '" + JWK_FIELD + "'");
    }

    try {
      // 解析 JWKSet
      JWKSet jwkSet = JWKSet.parse(jwkJson);
      return new ImmutableJWKSet<>(jwkSet);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse JWK JSON from Vault", e);
    }
  }

  /**
   * Bean for JwtDecoder that uses the JWKSource.
   *
   * @param jwkSource the JWKSource bean
   * @return JwtDecoder for decoding JWTs
   */
  @Bean
  @ConditionalOnMissingBean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }
}
