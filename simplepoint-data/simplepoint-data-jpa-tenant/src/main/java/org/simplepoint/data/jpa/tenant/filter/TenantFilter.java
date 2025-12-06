package org.simplepoint.data.jpa.tenant.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.data.datasource.context.DataSourceContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter for handling tenant-specific requests.
 * 处理租户特定请求的过滤器
 */
@Slf4j
public class TenantFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null) {
      String name = authentication.getClass().getName();
      if ("org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken".equals(name)) {
        Object principal = authentication.getPrincipal();
        if (principal != null) {
          if (principal.getClass().getName().equals("org.springframework.security.oauth2.jwt.Jwt")) {
            try {
              Field claims = principal.getClass().getDeclaredField("claims");
              claims.setAccessible(true);
              Object claimsValue = claims.get(principal);
              if (claimsValue instanceof Map claimsMap) {
                Object tenant = claimsMap.get("tenant");
                DataSourceContextHolder.set(tenant.toString());
                log.info("tenant [{}] is set", tenant);
              }
            } catch (NoSuchFieldException | IllegalAccessException e) {
              log.warn("Failed to extract tenant from JWT claims", e);
            }
          }
        }
      }
    }
    filterChain.doFilter(request, response);
  }
}
