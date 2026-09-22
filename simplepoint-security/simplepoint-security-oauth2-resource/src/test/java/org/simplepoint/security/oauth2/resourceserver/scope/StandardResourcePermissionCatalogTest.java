package org.simplepoint.security.oauth2.resourceserver.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.security.entity.ResourceScopeType;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class StandardResourcePermissionCatalogTest {
  @TableQuery({@QueryField("name")})
  static class Order extends TenantBaseEntityImpl<String> { String name; }
  static class Orders extends BaseController<BaseService<Order, String>, Order, String> {
    Orders() { super(mock(BaseService.class)); }
    @GetMapping @PreAuthorize("hasAuthority('example.orders.view')")
    public void list() {}
  }

  @Test
  void discoversAnUnlistedStandardPageAndUsesItForInheritedSchema() throws Exception {
    var scopes = new ClasspathResourceScopeRegistry(Map.of("example.orders.view", Set.of(ResourceScopeType.TENANT)),
        Map.of("example.orders.view", "example.orders.view"));
    var controller = new Orders();
    var mapping = mock(RequestMappingHandlerMapping.class);
    when(mapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/orders").build(),
        new HandlerMethod(controller, "list")));
    var beans = new StaticListableBeanFactory(Map.of("mapping", mapping));
    var catalog = new StandardResourcePermissionCatalog(beans.getBeanProvider(RequestMappingHandlerMapping.class), scopes);
    var model = catalog.entries().get("example.orders.view");
    assertThat(model.data()).isTrue();
    assertThat(model.fieldModels()).containsExactly(Order.class.getName());
    assertThat(model.commonProperties()).containsKeys("name", "id", "createdBy", "createOrgDeptId");
    var interceptor = new ResourceScopeHandlerInterceptor(scopes);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    var handler = new HandlerMethod(controller, "schema");
    try {
      assertThat(interceptor.preHandle(request, response, handler)).isTrue();
      assertThat(ResourcePolicyContext.get()).isEqualTo("example.orders.view");
    } finally { interceptor.afterCompletion(request, response, handler, null); }
    assertThat(ResourcePolicyContext.get()).isNull();
  }
}
