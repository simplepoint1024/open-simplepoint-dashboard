package org.simplepoint.plugin.notification.rest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables lightweight SSE heartbeat scheduling for notification delivery. */
@Configuration
@EnableScheduling
public class NotificationSchedulingConfiguration {
}
