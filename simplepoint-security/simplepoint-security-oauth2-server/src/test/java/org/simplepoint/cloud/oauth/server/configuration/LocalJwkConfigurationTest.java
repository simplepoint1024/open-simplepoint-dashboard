package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class LocalJwkConfigurationTest {
  @TempDir Path directory;

  @Test
  void configuredKeySurvivesNewConfigurationInstances() throws Exception {
    RSAKey key = key("persisted");
    Path file = write("keys.json", new JWKSet(key));
    LocalJwkConfiguration first = new LocalJwkConfiguration();
    LocalJwkConfiguration restarted = new LocalJwkConfiguration();
    String original = token(first, file);
    String afterRestart = token(restarted, file);
    var decoder =
        NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.PS256)
            .build();
    assertThat(decoder.decode(original).getSubject()).isEqualTo("restart-test");
    assertThat(decoder.decode(afterRestart).getHeaders().get("kid")).isEqualTo("persisted");
  }

  @Test
  void rotatedKeyKeepsOldPublicKeyAndSignsWithOnlyCurrentPrivateKey() throws Exception {
    RSAKey old = key("retired"), current = key("current");
    Path file = write("rotated.json", new JWKSet(List.of(current, old.toPublicJWK())));
    String encoded = token(new LocalJwkConfiguration(), file);
    var decoder =
        NimbusJwtDecoder.withPublicKey(current.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.PS256)
            .build();
    assertThat(decoder.decode(encoded).getHeaders().get("kid")).isEqualTo("current");
  }

  @Test
  void configuredMissingMalformedOrOversizedSecretNeverFallsBack() throws Exception {
    LocalJwkConfiguration config = new LocalJwkConfiguration();
    assertThatThrownBy(() -> config.jwkSource(directory.resolve("missing").toString()))
        .isInstanceOf(IllegalStateException.class);
    Path malformed = directory.resolve("malformed");
    Files.writeString(malformed, "private-secret-do-not-log");
    assertThatThrownBy(() -> config.jwkSource(malformed.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessageNotContaining("private-secret");
    Files.writeString(malformed, "x".repeat(65537));
    assertThatThrownBy(() -> config.jwkSource(malformed.toString()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAmbiguousUnsupportedAndPublicOnlySets() throws Exception {
    LocalJwkConfiguration config = new LocalJwkConfiguration();
    RSAKey first = key("first"), second = key("second");
    for (JWKSet invalid :
        List.of(
            new JWKSet(),
            new JWKSet(first.toPublicJWK()),
            new JWKSet(List.of(first, second)),
            new JWKSet(
                List.of(first, new RSAKey.Builder(second.toPublicJWK()).keyID("first").build())),
            new JWKSet(new RSAKey.Builder(first).algorithm(JWSAlgorithm.RS256).build()))) {
      Path file = write("invalid.json", invalid);
      assertThatThrownBy(() -> config.jwkSource(file.toString()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private RSAKey key(String id) throws Exception {
    return new RSAKeyGenerator(2048).keyID(id).algorithm(JWSAlgorithm.PS256).generate();
  }

  private Path write(String name, JWKSet keys) throws Exception {
    Path file = directory.resolve(name);
    Files.writeString(file, keys.toString(false));
    return file;
  }

  private String token(LocalJwkConfiguration config, Path file) {
    var claims =
        JwtClaimsSet.builder()
            .subject("restart-test")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    return config
        .jwtEncoder(config.jwkSource(file.toString()))
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.PS256).build(), claims))
        .getTokenValue();
  }
}
