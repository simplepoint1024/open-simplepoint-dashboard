package org.simplepoint.cloud.oauth.server.configuration.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

/**
 * 使用 Vault Transit 引擎进行 JWT 签名的 JwtEncoder 实现.
 */
public class VaultTransitJwtEncoder implements JwtEncoder {

  private final VaultTemplate vaultTemplate;
  private final ObjectMapper mapper;
  private final String keyName;

  /**
   * 构造函数.
   *
   * @param vaultTemplate VaultTemplate 实例
   * @param mapper        用于 JSON 序列化的 ObjectMapper
   * @param keyName       Vault Transit 密钥名称
   */
  public VaultTransitJwtEncoder(VaultTemplate vaultTemplate, ObjectMapper mapper, String keyName) {
    this.vaultTemplate = vaultTemplate;
    this.mapper = mapper; // 使用 Spring Boot 配置好的 ObjectMapper
    this.keyName = keyName;
  }

  @Override
  public Jwt encode(JwtEncoderParameters parameters) {
    try {
      VaultResponse keyMeta = vaultTemplate.read("transit/keys/" + keyName);
      String keyId = keyMeta != null && keyMeta.getData() != null
          ? String.valueOf(keyMeta.getData().get("latest_version"))
          : null;

      JwsHeader headers = Objects.requireNonNull(parameters.getJwsHeader(), "JwsHeader is required");
      JwtClaimsSet claims = Objects.requireNonNull(parameters.getClaims(), "JwtClaimsSet is required");

      String kid = keyId != null ? keyId : headers.getKeyId();

      JWSHeader jwsHeader = new JWSHeader.Builder(JWSAlgorithm.PS256)
          .keyID(kid)
          .type(JOSEObjectType.JWT)
          .build();

      String payloadJson = mapper.writeValueAsString(claims.getClaims());
      Payload payload = new Payload(payloadJson);
      JWSObject jwsObject = new JWSObject(jwsHeader, payload);

      byte[] signingInputBytes = jwsObject.getSigningInput();

      // Vault 默认 RSA 使用 PKCS1v1.5（相当于 RS256），要用 PS256 必须显式指定 signature_algorithm=pss
      Map<String, Object> body = new HashMap<>();
      body.put("input", Base64.getEncoder().encodeToString(signingInputBytes));
      body.put("signature_algorithm", "pss");
      body.put("hash_algorithm", "sha2-256");
      body.put("salt_length", 32); // 显式使用 32 字节盐长，匹配 PS256 默认验签行为

      VaultResponse response = vaultTemplate.write("transit/sign/" + keyName, body);
      String signature = response.getRequiredData().get("signature").toString();
      String sigPart = signature.substring(signature.lastIndexOf(':') + 1);

      byte[] sigBytes = Base64.getDecoder().decode(sigPart);
      String sigBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);

      String tokenValue = new String(signingInputBytes, StandardCharsets.UTF_8) + "." + sigBase64Url;

      Instant issuedAt = claims.getIssuedAt();
      Instant expiresAt = claims.getExpiresAt();

      return Jwt.withTokenValue(tokenValue)
          .headers(h -> h.putAll(headers.getHeaders()))
          .claims(c -> c.putAll(claims.getClaims()))
          .issuedAt(issuedAt)
          .expiresAt(expiresAt)
          .build();

    } catch (Exception e) {
      throw new IllegalStateException("Vault Transit signing failed", e);
    }
  }
}
