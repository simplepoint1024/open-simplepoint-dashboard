package org.simplepoint.security.context;

import java.util.Map;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.remoting.RemoteContract;

/**
 * AuthorizationContextService defines the contract for calculating the authorization context.
 *
 * <p>Implementations provide specific logic for determining authorization information from
 * supplied attributes.
 */
@RemoteContract(name = "security.authorization-context")
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
