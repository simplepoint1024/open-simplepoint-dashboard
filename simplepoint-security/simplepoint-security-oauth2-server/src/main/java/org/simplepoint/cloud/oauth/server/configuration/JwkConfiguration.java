/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;

/**
 * Configuration class for JSON Web Key (JWK) management.
 * JSON Web Key (JWK) 管理的配置类
 */
@Configuration
public class JwkConfiguration {

  /**
   * Creates a JWK source for managing security context.
   * 创建一个 JWK 源以管理安全上下文
   *
   * @return JWK source JWK 源
   */
  @Bean
  @ConditionalOnMissingBean
  public JWKSource<SecurityContext> jwkSource() {
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
    return new ImmutableJWKSet<>(jwkSet);
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

  /**
   * Creates a JWT decoder using the JWK source.
   * 使用 JWK 源创建 JWT 解码器
   *
   * @param jwkSource the JWK source JWK 源
   * @return JWT decoder JWT 解码器
   */
  @Bean
  @ConditionalOnMissingBean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }
}
