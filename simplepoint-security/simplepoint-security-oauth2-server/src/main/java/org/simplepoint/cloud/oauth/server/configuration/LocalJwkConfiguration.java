package org.simplepoint.cloud.oauth.server.configuration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/** Local JWT signing key configuration. */
@Configuration
public class LocalJwkConfiguration {

  /**
   * Provides RSA signing keys from an operator-managed local file, or an ephemeral development key.
   *
   * @param keySetFile private JWK set file; production deployments must persist this secret
   * @return signing source with optional retired public verification keys
   */
  @Bean
  @ConditionalOnMissingBean
  public JWKSource<SecurityContext> jwkSource(
      @Value("${simplepoint.authorization.signing-key-set-file:}") final String keySetFile) {
    if (StringUtils.hasText(keySetFile)) {
      return new ImmutableJWKSet<>(readKeySet(Path.of(keySetFile)));
    }
    KeyPair keyPair = generateRsaKey();
    RSAKey rsaKey =
        new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .privateKey((RSAPrivateKey) keyPair.getPrivate())
            .keyID(UUID.randomUUID().toString())
            .algorithm(JWSAlgorithm.PS256)
            .build();
    return new ImmutableJWKSet<>(new JWKSet(rsaKey));
  }

  private static JWKSet readKeySet(final Path file) {
    try {
      // Key files are small, local secrets. Bound reads and never expose their content on failure.
      final byte[] bytes;
      try (var input = Files.newInputStream(file)) {
        bytes = input.readNBytes(65537);
      }
      if (bytes.length > 65536) {
        throw new IllegalArgumentException("Signing key set exceeds its size limit");
      }
      final JWKSet keys = JWKSet.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      final Set<String> ids = new HashSet<>();
      int signingKeys = 0;
      for (JWK key : keys.getKeys()) {
        if (!(key instanceof RSAKey rsa)
            || rsa.size() < 2048
            || !JWSAlgorithm.PS256.equals(key.getAlgorithm())
            || !StringUtils.hasText(key.getKeyID())
            || !ids.add(key.getKeyID())
            || (key.getKeyUse() != null
                && !com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(key.getKeyUse()))) {
          throw new IllegalArgumentException(
              "Signing key set requires unique PS256 RSA keys of at least 2048 bits");
        }
        if (key.isPrivate()) {
          // Validate private material now; do not defer a corrupt secret to the first login.
          rsa.toRSAPrivateKey();
          signingKeys++;
        }
      }
      if (signingKeys != 1) {
        throw new IllegalArgumentException(
            "Signing key set requires exactly one private signing key");
      }
      return keys;
    } catch (IOException | ParseException | com.nimbusds.jose.JOSEException ex) {
      // Parsing exceptions can contain submitted secret fragments; do not attach them to startup
      // logs.
      throw new IllegalStateException("Cannot load the configured authorization signing key set");
    }
  }

  /** Provides the JWT encoder used by Spring Authorization Server. */
  @Bean
  @ConditionalOnMissingBean
  public JwtEncoder jwtEncoder(final JWKSource<SecurityContext> jwkSource) {
    // Retired public keys remain discoverable for verification, but cannot be signing candidates.
    return new NimbusJwtEncoder(
        (selector, context) ->
            jwkSource.get(selector, context).stream().filter(JWK::isPrivate).toList());
  }

  private static KeyPair generateRsaKey() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      return keyPairGenerator.generateKeyPair();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to generate local RSA signing key", ex);
    }
  }
}
