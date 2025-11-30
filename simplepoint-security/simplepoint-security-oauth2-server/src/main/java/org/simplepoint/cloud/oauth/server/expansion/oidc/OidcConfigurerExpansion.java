/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.expansion.oidc;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OidcConfigurer;

/**
 * Interface for expanding OIDC configuration.
 * 用于扩展 OIDC 配置的接口
 */
public interface OidcConfigurerExpansion extends Customizer<OidcConfigurer> {
}
