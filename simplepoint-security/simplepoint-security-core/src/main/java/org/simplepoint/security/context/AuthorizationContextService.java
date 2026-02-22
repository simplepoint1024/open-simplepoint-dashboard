package org.simplepoint.security.context;

import java.util.Map;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;

/**
 * AuthorizationContextService defines the contract for calculating the authorization context based on provided attributes.
 * It provides a method to calculate the authorization context, which can be implemented by different classes to provide specific logic for determining the authorization information.
 */
@AmqpRemoteClient(to = "security.authorization-context")
public interface AuthorizationContextService {

  /**
   * Calculates the authorization context based on the provided tenant ID, user ID, context ID, and additional attributes.
   *
   * @param tenantId   the ID of the tenant
   * @param userId     the ID of the user
   * @param contextId  the ID of the context
   * @param attributes a map of additional attributes that may be used in the calculation
   * @return the calculated AuthorizationContext
   */
  AuthorizationContext calculate(String tenantId, String userId, String contextId, Map<String, String> attributes);
}
