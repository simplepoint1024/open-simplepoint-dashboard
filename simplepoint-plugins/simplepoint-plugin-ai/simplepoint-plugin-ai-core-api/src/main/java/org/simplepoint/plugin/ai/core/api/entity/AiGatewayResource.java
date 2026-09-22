package org.simplepoint.plugin.ai.core.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Routing metadata only: never contains prompts, output, or credentials. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "simpoint_ai_gateway_resources")
public class AiGatewayResource {

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "api_key_id", nullable = false, length = 64)
  private String apiKeyId;

  @Column(name = "provider_definition_id", nullable = false, length = 64)
  private String providerDefinitionId;

  @Column(name = "model_definition_id", nullable = false, length = 64)
  private String modelDefinitionId;

  @Column(name = "public_model_id", nullable = false, length = 256)
  private String publicModelId;

  @Column(name = "resource_type", nullable = false, length = 32)
  private String resourceType;

  @Column(name = "upstream_id", nullable = false, length = 256)
  private String upstreamId;

  @Column(name = "invocation_id", length = 64)
  private String invocationId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
