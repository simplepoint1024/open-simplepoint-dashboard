/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.EndpointRateLimitRule;

/**
 * Repository abstraction for endpoint-level rate-limit rules.
 */
public interface EndpointRateLimitRuleRepository extends BaseRepository<EndpointRateLimitRule, String> {
}
