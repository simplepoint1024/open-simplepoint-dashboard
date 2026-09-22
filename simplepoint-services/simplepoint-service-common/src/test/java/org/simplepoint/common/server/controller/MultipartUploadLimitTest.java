package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

/** Exercises actual servlet multipart parsing, which MockMvc multipart requests bypass. */
class MultipartUploadLimitTest {
  private static final int MB = 1024 * 1024;

  @Test
  void configuredLimitsAcceptWidgetsAndRejectOversizedFiles() throws Exception {
    var environment = new StandardEnvironment();
    environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
    var multipart = Binder.get(environment)
        .bind("spring.servlet.multipart", MultipartProperties.class).get();
    var config = multipart.createMultipartConfig();
    assertEquals(20L * MB, config.getMaxFileSize());
    assertTrue(config.getMaxRequestSize() > config.getMaxFileSize());
    assertEquals(0, config.getFileSizeThreshold(), "Large uploads must spool to disk");

    var server = new TomcatServletWebServerFactory(0).getWebServer(context -> {
      var servlet = context.addServlet("upload-limit-test", new HttpServlet() {
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
          try {
            response.getWriter().print(request.getPart("file").getSize());
          } catch (IllegalStateException tooLarge) {
            response.setStatus(413);
          }
        }
      });
      servlet.setMultipartConfig(config);
      servlet.addMapping("/upload");
    });
    try (var client = HttpClient.newHttpClient()) {
      server.start();
      // Above Spring Boot's former 1 MB default, at each UI limit and at the server limit.
      for (int size : new int[]{2 * MB, 5 * MB, 10 * MB, 20 * MB}) {
        var response = upload(client, server.getPort(), size);
        assertEquals(200, response.statusCode(), "Upload size: " + size);
        assertEquals(String.valueOf(size), response.body());
      }
      assertEquals(413, upload(client, server.getPort(), 21 * MB).statusCode());
    } finally {
      server.stop();
      server.destroy();
    }
  }

  private HttpResponse<String> upload(HttpClient client, int port, int size) throws Exception {
    byte[] header = ("--upload-boundary\r\nContent-Disposition: form-data; name=\"file\"; "
        + "filename=\"upload.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        .getBytes(StandardCharsets.UTF_8);
    byte[] footer = "\r\n--upload-boundary--\r\n".getBytes(StandardCharsets.UTF_8);
    var body = HttpRequest.BodyPublishers.concat(
        HttpRequest.BodyPublishers.ofByteArray(header),
        HttpRequest.BodyPublishers.ofByteArray(new byte[size]),
        HttpRequest.BodyPublishers.ofByteArray(footer));
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
        .header("Content-Type", "multipart/form-data; boundary=upload-boundary")
        .POST(body).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
