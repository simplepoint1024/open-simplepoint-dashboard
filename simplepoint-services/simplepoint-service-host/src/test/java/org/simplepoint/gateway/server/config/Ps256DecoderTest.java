package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

/**
 * Verifies that the configured decoder can validate PS256 JWTs with a matching RSA public key.
 */
class Ps256DecoderTest {

  @Test
  void decodePs256TokenWithPublicKey() throws Exception {
    KeyPair keyPair = generateKeyPair();
    String token = createSignedToken((RSAPrivateKey) keyPair.getPrivate(), "test-kid");

    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
        .withPublicKey((RSAPublicKey) keyPair.getPublic())
        .signatureAlgorithm(SignatureAlgorithm.PS256)
        .build();

    Jwt jwt = decoder.decode(token).block();
    assertThat(jwt).isNotNull();
    assertThat(jwt.getHeaders().get("kid")).isEqualTo("test-kid");
    assertThat(jwt.getHeaders().get("alg")).isEqualTo("PS256");
    assertThat(jwt.getSubject()).isEqualTo("system");
  }

  private KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private String createSignedToken(RSAPrivateKey privateKey, String keyId) throws JOSEException {
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.PS256)
            .type(JOSEObjectType.JWT)
            .keyID(keyId)
            .build(),
        new JWTClaimsSet.Builder()
            .subject("system")
            .audience("simplepoint-client")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build());
    jwt.sign(new RSASSASigner(privateKey));
    return jwt.serialize();
  }
}
