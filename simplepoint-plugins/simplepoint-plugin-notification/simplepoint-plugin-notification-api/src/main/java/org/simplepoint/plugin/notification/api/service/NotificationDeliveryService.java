package org.simplepoint.plugin.notification.api.service;

import org.simplepoint.plugin.notification.api.vo.NotificationDeliveryCommand;
import org.simplepoint.remoting.RemoteContract;

/** Trusted service-to-service boundary for idempotent notification delivery. */
@RemoteContract(name = "notification.delivery")
public interface NotificationDeliveryService {

  /** Creates and publishes a user notification, returning the existing ID on retry. */
  String deliver(NotificationDeliveryCommand command);
}
