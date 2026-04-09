/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.repository.LoginLogRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for login logs.
 */
@Repository
public interface JpaLoginLogRepository extends BaseRepository<LoginLog, String>, LoginLogRepository {
}
