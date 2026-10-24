package org.simplepoint.plugin.storage.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.storage.api.service.ObjectStorageObjectService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageProviderConfigService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageTenantQuotaService;
import org.simplepoint.plugin.storage.rest.controller.ObjectStorageAdminController;
import org.simplepoint.plugin.storage.rest.controller.ObjectStorageServiceController;
import org.simplepoint.plugin.storage.rest.controller.ObjectStorageSourceController;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class StorageHttpCompositionTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
          .withUserConfiguration(
              ObjectStorageAdminController.class,
              ObjectStorageServiceController.class,
              ObjectStorageSourceController.class)
          .withBean(ObjectStorageObjectService.class, () -> mock(ObjectStorageObjectService.class))
          .withBean(
              ObjectStorageProviderConfigService.class,
              () -> mock(ObjectStorageProviderConfigService.class))
          .withBean(
              ObjectStorageTenantQuotaService.class,
              () -> mock(ObjectStorageTenantQuotaService.class))
          .withBean(ObjectStorageSourceService.class, () -> mock(ObjectStorageSourceService.class));

  @Test
  void selectingStorageDomainDoesNotExposeItsUploadOrAdministrativeEndpoints() {
    var domain = catalog.plan(List.of("storage"));
    assertThat(ModuleHttpPlan.from(domain).routes()).isEmpty();
    assertThat(domain.ownerOf(ObjectStorageServiceController.class)).isEmpty();
    assertThat(domain.ownerOf(ObjectStorageAdminController.class)).isEmpty();
    assertThat(domain.ownerOf(ObjectStorageSourceController.class)).isEmpty();
  }

  @Test
  void uploadsAliasesAndPrivateDownloadRetainAllMappingsUnderCommon() {
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
    var modules = catalog.plan(List.of("storage-http"));
    assertThat(ModuleHttpPlan.from(modules).routes().get("storage-http"))
        .isEqualTo(
            Set.of(
                "/common/platform/object-storage",
                "/common/object-storage",
                "/common/internal/object-storage"));
    assertThat(
            modules.ownerOf(ObjectStorageSourceController.class).orElseThrow().consumes().values())
        .contains(ObjectStorageSourceService.class);
    runner
        .withBean(ModulePlan.class, () -> modules)
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
                                  path -> {
                                    assertThat(path).startsWith("/common/");
                                    mounted.add(
                                        route.getMethodsCondition()
                                            + " "
                                            + path.substring("/common".length()));
                                  }));
              assertThat(mounted).isEqualTo(original);
              assertThat(mounted.size()).isGreaterThan(20);
            });
  }
}
