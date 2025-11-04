/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.security.entity.Permissions;

/**
 * PermissionsService interface provides methods for handling permissions-related operations.
 * It includes a custom query to retrieve permission authorities by role authority.
 */
@AmqpRemoteClient(to = "security.permissions")
public interface PermissionsService extends BaseService<Permissions, String> {
}
