package org.simplepoint.api.security.service;

import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;

/**
 * JSON Schema service.
 */
@AmqpRemoteClient(to = "security.schema")
public interface JsonSchemaDetailsService extends BaseDetailsService {

}
