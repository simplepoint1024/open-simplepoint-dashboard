package org.simplepoint.mcp.gateway.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.config.McpGatewayConfiguration;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;
import org.simplepoint.mcp.gateway.publication.McpPublicationControlPlaneClient;
import org.simplepoint.mcp.gateway.publication.McpPublicationDispatcherServlet;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.simplepoint.mcp.gateway.rest.McpGatewayExceptionHandler;
import org.simplepoint.mcp.gateway.rest.McpGatewayInternalController;
import org.simplepoint.mcp.gateway.rest.McpOauthClientMetadataController;
import org.simplepoint.mcp.gateway.rest.McpProtectedResourceMetadataController;
import org.simplepoint.mcp.gateway.security.McpOauthClientMetadataDocument;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class McpGatewayCompositionTest {
  private final ModulePlan plan =
      ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("mcp-gateway"));
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
          .withUserConfiguration(
              McpGatewayInternalController.class,
              McpProtectedResourceMetadataController.class,
              McpOauthClientMetadataController.class,
              McpGatewayExceptionHandler.class)
          .withBean("defaultRemoteMcpGatewayOperations", McpGatewayOperations.class, () -> mock(McpGatewayOperations.class))
          .withBean("httpMcpGatewayOperations", McpGatewayOperations.class, () -> mock(McpGatewayOperations.class))
          .withBean(McpPublicationRegistry.class, () -> mock(McpPublicationRegistry.class))
          .withBean(
              McpOauthClientMetadataDocument.class,
              () -> new McpOauthClientMetadataDocument(new McpGatewayProperties()));

  @Test
  void protocolModuleNeedsNoDomainPersistenceAndKeepsEveryMvcRoute() {
    assertThat(plan.modules()).extracting(module -> module.id()).containsExactly("mcp-gateway");
    var original = new TreeSet<String>();
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          context
              .getBean(RequestMappingHandlerMapping.class)
              .getHandlerMethods()
              .keySet()
              .forEach(
                  route ->
                      route
                          .getPatternValues()
                          .forEach(path -> original.add(route.getMethodsCondition() + " " + path)));
        });
    runner
        .withBean(ModulePlan.class, () -> plan)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var mounted = new TreeSet<String>();
              context
                  .getBean(RequestMappingHandlerMapping.class)
                  .getHandlerMethods()
                  .keySet()
                  .forEach(
                      route ->
                          route
                              .getPatternValues()
                              .forEach(
                                  path -> mounted.add(route.getMethodsCondition() + " " + path)));
              assertThat(mounted).isEqualTo(original).hasSize(11);
              var advice =
                  ControllerAdviceBean.findAnnotatedBeans(context).stream()
                      .filter(bean -> bean.getBeanType() == McpGatewayExceptionHandler.class)
                      .findFirst()
                      .orElseThrow();
              assertThat(advice.isApplicableToBeanType(McpGatewayInternalController.class))
                  .isTrue();
              assertThat(advice.isApplicableToBeanType(getClass())).isFalse();
            });
  }

  @Test
  void nativeServletUsesTheSamePathAsDeploymentRouting() {
    var roots = ModuleHttpPlan.from(plan).routes().get("mcp-gateway");
    assertThat(roots)
        .contains(
            McpPublicationDispatcherServlet.PATH,
            McpProtectedResourceMetadataController.PATH,
            McpOauthClientMetadataDocument.PATH);
    assertThat(roots).doesNotContain("/", "/.well-known", "/internal");
    var servlet =
        new McpGatewayConfiguration()
            .mcpPublicationServlet(
                mock(McpPublicationRegistry.class),
                mock(McpCancellationRegistry.class),
                mock(McpPublicationControlPlaneClient.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new McpGatewayProperties());
    assertThat(servlet.getUrlMappings())
        .containsExactly(McpPublicationDispatcherServlet.PATH + "/*");
    assertThat(servlet.isAsyncSupported()).isTrue();
  }
}
