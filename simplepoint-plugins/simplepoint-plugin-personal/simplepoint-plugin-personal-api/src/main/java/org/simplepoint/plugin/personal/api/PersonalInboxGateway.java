package org.simplepoint.plugin.personal.api;

import org.simplepoint.plugin.personal.api.PersonalModels.Delivery;
import org.simplepoint.plugin.personal.api.PersonalModels.DeliveryTarget;
import org.simplepoint.plugin.personal.api.PersonalModels.MessageView;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoView;

/**
 * Trusted in-process business integration. No unauthenticated HTTP/RPC exposure. Call after the
 * originating business transaction commits (prefer an outbox). Retries use the same
 * source/sourceKey. Concurrent unique-key conflicts are retryable. External todo outcomes come from
 * the business system, never the personal UI.
 */
public interface PersonalInboxGateway {
  /** Delivers a todo idempotently to a trusted, explicit user/workspace target. */
  TodoView deliverTodo(DeliveryTarget target, Delivery delivery);

  /** Delivers a message idempotently to a trusted, explicit user/workspace target. */
  MessageView deliverMessage(DeliveryTarget target, Delivery delivery);

  /** Records an authoritative source-system terminal outcome idempotently. */
  TodoView resolveTodo(DeliveryTarget target, String source, String sourceKey, String outcome);
}
