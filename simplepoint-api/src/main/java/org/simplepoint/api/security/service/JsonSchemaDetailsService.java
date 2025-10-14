package org.simplepoint.api.security.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.simple.SimpleFieldPermissions;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;

/**
 * JSON Schema service.
 */
@AmqpRemoteService
@AmqpRemoteClient(to = "security.schema")
public interface JsonSchemaDetailsService extends BaseDetailsService {

  /**
   * Get schema by permissions authority.
   *
   * @param roles     roles.
   * @param className class name.
   * @return schema.
   */
  Set<SimpleFieldPermissions> loadSchemaPropertiesPermissions(Set<String> roles, String className);

  /**
   * Get current user schema by permissions authority.
   *
   * @param className class name.
   * @return schema.
   */
  Set<SimpleFieldPermissions> loadCurrentUserSchemaPropertiesPermissions(BaseUser currentUser, String className);
}
