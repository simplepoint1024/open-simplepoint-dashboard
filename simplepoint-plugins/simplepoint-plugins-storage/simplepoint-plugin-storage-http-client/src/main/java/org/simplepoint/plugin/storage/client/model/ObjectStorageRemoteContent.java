/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.model;

/**
 * Content returned by the unified remote object-storage API.
 *
 * @param content       object bytes
 * @param fileName      original file name
 * @param contentType   media type
 * @param contentLength object length
 */
public record ObjectStorageRemoteContent(
    byte[] content,
    String fileName,
    String contentType,
    long contentLength
) {
}
