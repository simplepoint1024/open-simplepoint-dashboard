package org.simplepoint.plugin.dna.core.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDriverArtifactDownloader;
import org.springframework.stereotype.Component;

/**
 * Default downloader for HTTP and HTTPS JDBC driver artifacts.
 */
@Component
public class HttpJdbcDriverArtifactDownloader implements JdbcDriverArtifactDownloader {

  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Override
  public boolean supports(final URI downloadUri) {
    if (downloadUri == null || downloadUri.getScheme() == null) {
      return false;
    }
    String scheme = downloadUri.getScheme().toLowerCase();
    return "http".equals(scheme) || "https".equals(scheme);
  }

  @Override
  public InputStream download(
      final JdbcDriverDefinition driver,
      final URI downloadUri
  ) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(downloadUri).GET().build();
    HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    int statusCode = response.statusCode();
    if (statusCode >= 200 && statusCode < 300) {
      return response.body();
    }
    try (InputStream body = response.body()) {
      if (body != null) {
        body.readAllBytes();
      }
    }
    throw new IllegalStateException("驱动下载失败，HTTP状态码: " + statusCode + ", 驱动编码: " + driver.getCode());
  }
}
