package org.simplepoint.common.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A filter that logs the request headers for debugging purposes.
 * This filter is ordered with the highest precedence to ensure it runs first.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@Slf4j
public class RequestHeaderLoggingFilter implements Filter {
  /**
   * Default constructor.
   */
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof HttpServletRequest httpRequest) {
      if (!httpRequest.getRequestURI().startsWith("/actuator")) {
        // 构造首行：METHOD URI
        StringBuilder sb = new StringBuilder()
            .append(httpRequest.getMethod())
            .append(" ")
            .append(httpRequest.getRequestURI())
            .append("\n");

        // 遍历所有 Header
        Enumeration<String> names = httpRequest.getHeaderNames();
        while (names.hasMoreElements()) {
          String name = names.nextElement();
          String value = httpRequest.getHeader(name);
          sb.append("  ")
              .append(name)
              .append(": ")
              .append(value)
              .append("\n");
        }

        // 输出日志
        log.info("\n{}", sb);
      }
    }
    chain.doFilter(request, response);
  }
}

