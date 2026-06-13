package org.simplepoint.plugin.storage.api.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ObjectStorageWriteResultTest {

  @Test
  void allArgsConstructorAndGetters() {
    ObjectStorageWriteResult result = new ObjectStorageWriteResult(
        "my-bucket", "images/photo.jpg", "abc123etag", "https://cdn.example.com/images/photo.jpg");

    assertEquals("my-bucket", result.getBucket());
    assertEquals("images/photo.jpg", result.getObjectKey());
    assertEquals("abc123etag", result.getETag());
    assertEquals("https://cdn.example.com/images/photo.jpg", result.getAccessUrl());
  }
}
