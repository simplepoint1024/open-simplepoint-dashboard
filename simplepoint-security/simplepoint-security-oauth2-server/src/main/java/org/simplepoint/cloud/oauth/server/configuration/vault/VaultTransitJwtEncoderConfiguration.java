package org.simplepoint.cloud.oauth.server.configuration.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.vault.core.VaultTemplate;

@Configuration
public class VaultTransitJwtEncoderConfiguration {

  private static final String KEY_NAME = "sas-jwt";

  @Bean
  public JwtEncoder jwtEncoder(VaultTemplate vaultTemplate, ObjectMapper objectMapper) {
    return new VaultTransitJwtEncoder(vaultTemplate, objectMapper, KEY_NAME);
  }

}
