package org.simplepoint.plugin.ai.core.repository;

import org.simplepoint.plugin.ai.core.api.entity.AiGatewayResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Durable key-specific bindings for native OpenAI response and item identifiers. */
@Repository
public interface AiGatewayResourceRepository extends JpaRepository<AiGatewayResource, String> {
}
