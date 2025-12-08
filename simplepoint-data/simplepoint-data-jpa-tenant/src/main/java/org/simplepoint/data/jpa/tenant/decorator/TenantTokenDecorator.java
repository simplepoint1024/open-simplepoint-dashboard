//package org.simplepoint.data.jpa.tenant.decorator;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import java.util.Map;
//import org.simplepoint.security.decorator.TokenDecorator;
//import org.simplepoint.security.entity.User;
//import org.springframework.security.core.Authentication;
//
///**
// * Tenant-specific implementation of AccessTokenDecorator.
// * 租户特定的访问令牌装饰器实现
// */
//public class TenantTokenDecorator implements TokenDecorator {
//  @Override
//  public Map<String, Object> resolveTokenClaims(Authentication authentication, String tokenType) {
//    if (authentication != null && authentication.getPrincipal() instanceof User user) {
//      ObjectNode decorator = user.getDecorator();
//      if (decorator != null) {
//        JsonNode tenant = decorator.get("tenant");
//        if (tenant != null) {
//          return Map.of("tenant", tenant.asText());
//        }
//      }
//    }
//    return Map.of();
//  }
//}
