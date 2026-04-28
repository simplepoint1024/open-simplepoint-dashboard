package org.simplepoint.plugin.storage.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjectStorageUploadRequestTest {

  @Test
  void settersAndGetters() {
    ObjectStorageUploadRequest req = new ObjectStorageUploadRequest();
    req.setProviderCode("minio-1");
    req.setDirectory("images/2025");
    req.setObjectKey("images/2025/photo.jpg");
    req.setFileName("photo.jpg");
    req.setSourceServiceName("simplepoint-common");

    assertEquals("minio-1", req.getProviderCode());
    assertEquals("images/2025", req.getDirectory());
    assertEquals("images/2025/photo.jpg", req.getObjectKey());
    assertEquals("photo.jpg", req.getFileName());
    assertEquals("simplepoint-common", req.getSourceServiceName());
  }

  @Test
  void defaultConstructorFieldsAreNull() {
    ObjectStorageUploadRequest req = new ObjectStorageUploadRequest();
    assertNull(req.getProviderCode());
    assertNull(req.getDirectory());
    assertNull(req.getObjectKey());
    assertNull(req.getFileName());
    assertNull(req.getSourceServiceName());
  }
}
