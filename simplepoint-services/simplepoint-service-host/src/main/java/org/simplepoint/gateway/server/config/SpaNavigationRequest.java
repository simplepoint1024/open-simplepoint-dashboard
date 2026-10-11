package org.simplepoint.gateway.server.config;

import java.util.Locale;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;

/** Detects browser document navigation without treating API requests as SPA pages. */
final class SpaNavigationRequest {

  private SpaNavigationRequest() {
  }

  static boolean isDocumentNavigation(ServerRequest request) {
    String fetchDestination = request.headers().firstHeader("Sec-Fetch-Dest");
    if (fetchDestination != null && "document".equals(fetchDestination.trim().toLowerCase(Locale.ROOT))) {
      return true;
    }

    try {
      return request.headers().accept().stream()
          .filter(mediaType -> mediaType.getQualityValue() > 0)
          .filter(mediaType -> !mediaType.isWildcardType() && !mediaType.isWildcardSubtype())
          .anyMatch(MediaType.TEXT_HTML::isCompatibleWith);
    } catch (InvalidMediaTypeException ignored) {
      return false;
    }
  }
}
