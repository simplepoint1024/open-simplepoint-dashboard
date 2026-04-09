/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.service;

import java.util.Optional;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Spring HTTP client abstraction for remote object-storage service calls.
 */
public interface ObjectStorageRemoteService {

  /**
   * Uploads a multipart file.
   *
   * @param file    multipart file
   * @param request upload request options
   * @return stored metadata
   */
  ObjectStorageObject upload(MultipartFile file, ObjectStorageUploadRequest request);

  /**
   * Uploads a resource.
   *
   * @param resource      resource to upload
   * @param fileName      file name used in multipart payload
   * @param contentType   content type
   * @param contentLength content length
   * @param request       upload request options
   * @return stored metadata
   */
  ObjectStorageObject upload(
      Resource resource,
      String fileName,
      String contentType,
      long contentLength,
      ObjectStorageUploadRequest request
  );

  /**
   * Queries stored metadata by id.
   *
   * @param id object id
   * @return metadata
   */
  Optional<ObjectStorageObject> metadata(String id);
}
