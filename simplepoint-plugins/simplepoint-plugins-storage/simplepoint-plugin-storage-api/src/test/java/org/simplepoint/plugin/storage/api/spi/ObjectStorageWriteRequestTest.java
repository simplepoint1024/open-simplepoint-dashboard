package org.simplepoint.plugin.storage.api.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectStorageWriteRequestTest {

  @Test
  void allArgsConstructorAndGetters() {
    InputStream stream = new ByteArrayInputStream(new byte[] {});
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
