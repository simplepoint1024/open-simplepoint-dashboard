package org.simplepoint.common.server.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import java.io.IOException;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.context.AuthorizationContextService;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RedisAuthorizationContextConfiguration is a configuration class for setting up Redis-based authorization context management.
 * This class can be used to define beans and configurations related to storing and retrieving authorization contexts in Redis.
 */
@Configuration
public class RedisAuthorizationContextConfiguration {

  /**
   * Creates a bean for AuthorizationContextResolver that uses Redis for caching authorization contexts.
   *
   * @param redisTemplate               the StringRedisTemplate for interacting with Redis
   * @param objectMapper                the ObjectMapper for serializing and deserializing AuthorizationContext objects
   * @param authorizationContextService the AuthorizationContextService for calculating authorization contexts when not found in cache
   * @return an instance of AuthorizationContextResolver configured to use Redis for caching
   */
  @Bean
  public AuthorizationContextResolver authorizationContextResolver(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      AuthorizationContextService authorizationContextService,
      OAuth2ResourceServerProperties resourceServerProperties
  ) throws GeneralException, IOException {
    return new AuthorizationContextResolver(
        authorizationContext -> {
          try {
            String json = objectMapper.writeValueAsString(authorizationContext);
            redisTemplate.opsForValue().set("auth:ctx:" + authorizationContext.getContextId(), json);
          } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize AuthorizationContext", e);
          }
          return null;
        },
        contextId -> {
          String json = redisTemplate.opsForValue().get("auth:ctx:" + contextId);
          if (json != null) {
            try {
              return objectMapper.readValue(json, AuthorizationContext.class);
            } catch (JsonProcessingException e) {
              throw new RuntimeException("Failed to deserialize AuthorizationContext", e);
            }
          }
          return null;
        },
        authorizationContextService,
        // 从 OIDC Provider Metadata 中获取 issuer URI，确保与 JWT 认证配置一致
        OIDCProviderMetadata.resolve(
            Issuer.parse(resourceServerProperties.getJwt().getIssuerUri())).getUserInfoEndpointURI()

    );
  }
}
