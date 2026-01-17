package org.simplepoint.cloud.oauth.server.unit.test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class GenerateJWK {
  @Test
  public void jwkSource() {
    // Generate an RSA key pair
    // 生成一个 RSA 密钥对
    KeyPair keyPair = generateRsaKey();

    // Extract public and private keys
    // 提取公钥和私钥
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

    // Create RSAKey with generated key pair
    // 使用生成的密钥对创建 RSAKey
    RSAKey rsaKey = new RSAKey.Builder(publicKey)
        .privateKey(privateKey)
        .keyID(UUID.randomUUID().toString())
        .build();

    // Create a JWK set and return as an immutable JWK source
    // 创建一个 JWK 集合并作为不可变的 JWK 源返回
    JWKSet jwkSet = new JWKSet(rsaKey);
    var jwk = new ImmutableJWKSet<>(jwkSet);
    System.out.println(jwkSet.toJSONObject());
  }

  /**
   * Generates an RSA key pair.
   * 生成一个 RSA 密钥对
   *
   * @return RSA key pair RSA 密钥对
   */
  private static KeyPair generateRsaKey() {
    KeyPair keyPair;
    try {
      // Initialize RSA key pair generator
      // 初始化 RSA 密钥对生成器
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);

      // Generate key pair
      // 生成密钥对
      keyPair = keyPairGenerator.generateKeyPair();
    } catch (Exception ex) {
      // Handle exception and throw an illegal state error
      // 处理异常并抛出非法状态错误
      throw new IllegalStateException(ex);
    }
    return keyPair;
  }
}
