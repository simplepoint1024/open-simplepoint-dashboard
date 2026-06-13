package org.simplepoint.plugin.storage.api.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ObjectStorageReadResultTest {

  @Test
  void allArgsConstructorAndGetters() {
    InputStream stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
    ObjectStorageReadResult result = new ObjectStorageReadResult(stream, "image/jpeg", 1024L, "photo.jpg");

    assertSame(stream, result.getInputStream());
    assertEquals("image/jpeg", result.getContentType());
    assertEquals(1024L, result.getContentLength());
    assertEquals("photo.jpg", result.getFileName());
  }
}
