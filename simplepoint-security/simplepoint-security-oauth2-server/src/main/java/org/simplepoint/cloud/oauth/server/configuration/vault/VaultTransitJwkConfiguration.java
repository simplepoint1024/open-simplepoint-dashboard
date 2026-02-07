package org.simplepoint.cloud.oauth.server.configuration.vault;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

/**
 * 从 Vault Transit 加载 RSA 公钥并暴露为 JWKS，算法统一标记为 PS256。
 */
@Configuration
public class VaultTransitJwkConfiguration {

  private static final String KEY_NAME = "sas-jwt";

  /**
   * JWKS 源，包含 Vault Transit 中所有版本的 RSA 公钥（kid=版本号）。
   *
   * @param vaultTemplate Vault 客户端
   * @return JWKSource
   */
  @Bean
  public JWKSource<SecurityContext> jwkSource(VaultTemplate vaultTemplate) {
    return (jwkSelector, securityContext) -> {
      try {
        VaultResponse response = vaultTemplate.read("transit/keys/" + KEY_NAME);
        if (response == null || response.getData() == null) {
          throw new IllegalStateException("Vault transit key metadata not found for key: " + KEY_NAME);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> keys = (Map<String, Object>) response.getData().get("keys");
        List<RSAKey> jwkList = new ArrayList<>();

        for (Map.Entry<String, Object> entry : keys.entrySet()) {
          String version = entry.getKey();
          @SuppressWarnings("unchecked")
          Map<String, Object> keyData = (Map<String, Object>) entry.getValue();
          String pem = (String) keyData.get("public_key");

          String pemClean = pem
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("[\\r\\n\\t ]", "");

          byte[] derBytes = Base64.getDecoder().decode(pemClean);
          X509EncodedKeySpec spec = new X509EncodedKeySpec(derBytes);
          KeyFactory kf = KeyFactory.getInstance("RSA");
          RSAPublicKey rsaPublicKey = (RSAPublicKey) kf.generatePublic(spec);

          RSAKey jwk = new RSAKey.Builder(rsaPublicKey)
              .keyID(version)
              .algorithm(JWSAlgorithm.PS256)
              .build();
          jwkList.add(jwk);
        }

        List<JWK> jwks = jwkList.stream().map(jwk -> (JWK) jwk).collect(Collectors.toList());
        return jwkSelector.select(new JWKSet(jwks));
      } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
        throw new IllegalStateException("Failed to build RSA public key from Vault transit", e);
      }
    };
  }
}
