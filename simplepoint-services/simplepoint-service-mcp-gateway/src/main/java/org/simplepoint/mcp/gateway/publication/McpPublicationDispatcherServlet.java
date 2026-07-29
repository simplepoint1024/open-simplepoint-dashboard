package org.simplepoint.mcp.gateway.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;

/**
 * Dispatches stable publication paths to cached official SDK transports.
 */
public class McpPublicationDispatcherServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;

  private final McpPublicationRegistry registry;

  private final McpCancellationRegistry cancellationRegistry;

  private final ObjectMapper objectMapper;

  private final int maxRequestBytes;

  /**
   * Creates the publication servlet dispatcher.
   */
  public McpPublicationDispatcherServlet(
      final McpPublicationRegistry registry,
      final McpCancellationRegistry cancellationRegistry,
      final ObjectMapper objectMapper,
      final McpGatewayProperties properties
  ) {
    this.registry = registry;
    this.cancellationRegistry = cancellationRegistry;
    this.objectMapper = objectMapper;
    this.maxRequestBytes = Math.max(1024, properties.getMaxProtocolRequestBytes());
  }

  @Override
  protected void service(
      final HttpServletRequest request,
      final HttpServletResponse response
  ) throws ServletException, IOException {
    String code = publicationCode(request);
    if (code == null) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    try {
      HttpServletRequest protocolRequest = inspect(code, request, response);
      if (protocolRequest != null) {
        registry.transport(code).service(protocolRequest, response);
      }
    } catch (IllegalArgumentException ex) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
    } catch (IllegalStateException ex) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
  }

  private HttpServletRequest inspect(
      final String code,
      final HttpServletRequest request,
      final HttpServletResponse response
  ) throws IOException {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return request;
    }
    byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
    if (body.length > maxRequestBytes) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return null;
    }
    JsonNode message;
    try {
      message = objectMapper.readTree(body);
    } catch (IOException ex) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST);
      return null;
    }
    String method = message.path("method").asText();
    if ("notifications/cancelled".equals(method)) {
      JsonNode parameters = message.path("params");
      cancellationRegistry.cancel(
          code,
          request.getHeader("Mcp-Session-Id"),
          requestId(parameters.get("requestId")),
          parameters.path("reason").asText(null)
      );
      response.setStatus(HttpServletResponse.SC_ACCEPTED);
      return null;
    }
    String requestId = requestId(message.get("id"));
    if (requestId != null) {
      request.setAttribute(
          McpCancellationRegistry.REQUEST_ID_CONTEXT_KEY,
          requestId
      );
    }
    return new CachedBodyRequest(request, body);
  }

  private static String requestId(final JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isTextual()) {
      return "s:" + value.asText();
    }
    if (value.isIntegralNumber()) {
      return "n:" + value;
    }
    return null;
  }

  private static String publicationCode(final HttpServletRequest request) {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null || !pathInfo.matches("/[a-z0-9][a-z0-9_-]{0,127}")) {
      return null;
    }
    return pathInfo.substring(1);
  }

  private static final class CachedBodyRequest
      extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(
        final HttpServletRequest request,
        final byte[] body
    ) {
      super(request);
      this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(
          getInputStream(),
          StandardCharsets.UTF_8
      ));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }

  private static final class ByteArrayServletInputStream
      extends ServletInputStream {

    private final ByteArrayInputStream input;

    private ByteArrayServletInputStream(final byte[] body) {
      this.input = new ByteArrayInputStream(body);
    }

    @Override
    public boolean isFinished() {
      return input.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(final ReadListener readListener) {
      if (readListener == null) {
        throw new IllegalArgumentException("ReadListener must not be null");
      }
      try {
        if (!isFinished()) {
          readListener.onDataAvailable();
        }
        if (isFinished()) {
          readListener.onAllDataRead();
        }
      } catch (IOException ex) {
        readListener.onError(ex);
      }
    }

    @Override
    public int read() {
      return input.read();
    }

    @Override
    public int read(
        final byte[] bytes,
        final int offset,
        final int length
    ) {
      return input.read(bytes, offset, length);
    }
  }
}
