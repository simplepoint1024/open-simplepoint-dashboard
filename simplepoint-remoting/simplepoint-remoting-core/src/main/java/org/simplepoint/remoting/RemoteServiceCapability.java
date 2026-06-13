/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.remoting;

/**
 * A provider capability that can satisfy a remoting contract.
 *
 * @param name logical service capability name
 * @param version contract version
 * @param providerId logical provider or application id
 */
public record RemoteServiceCapability(String name, String version, String providerId) {
}
