/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.EndpointRateLimitRule;

/**
 * Endpoint-level rate-limit rule CRUD service.
 */
public interface EndpointRateLimitRuleService extends BaseService<EndpointRateLimitRule, String> {
}
