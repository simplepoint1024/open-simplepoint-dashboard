package org.simplepoint.plugin.storage.api.spi;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

class ObjectStorageReadResultTest {

  @Test
  void allArgsConstructorAndGetters() {
    InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
    ObjectStorageReadResult result = new ObjectStorageReadResult(stream, "image/jpeg", 1024L, "photo.jpg");

    assertSame(stream, result.getInputStream());
    assertEquals("image/jpeg", result.getContentType());
    assertEquals(1024L, result.getContentLength());
    assertEquals("photo.jpg", result.getFileName());
  }
}

class ObjectStorageWriteRequestTest {

  @Test
  void allArgsConstructorAndGetters() {
    InputStream stream = new ByteArrayInputStream(new byte[]{});
    Map<String, String> meta = new HashMap<>();
    meta.put("x-custom", "value");

    ObjectStorageWriteRequest request = new ObjectStorageWriteRequest(
        "bucket", "dir/file.txt", "original.txt", "text/plain", 512L, meta, stream);

    assertEquals("bucket", request.getBucket());
    assertEquals("dir/file.txt", request.getObjectKey());
    assertEquals("original.txt", request.getOriginalFileName());
    assertEquals("text/plain", request.getContentType());
    assertEquals(512L, request.getContentLength());
    assertSame(meta, request.getMetadata());
    assertSame(stream, request.getInputStream());
  }
}
