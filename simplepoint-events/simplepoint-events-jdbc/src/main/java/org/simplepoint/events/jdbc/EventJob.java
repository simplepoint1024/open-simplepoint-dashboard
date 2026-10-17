/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.jdbc;

import org.simplepoint.events.api.EventMessage;

/** A fenced lease on durable delivery work. */
public record EventJob(String id, String subscription, EventMessage event,
                       String leaseToken, int attempts) {
}
