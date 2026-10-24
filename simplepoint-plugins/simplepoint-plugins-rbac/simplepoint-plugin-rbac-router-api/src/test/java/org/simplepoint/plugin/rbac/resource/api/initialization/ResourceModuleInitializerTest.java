package org.simplepoint.plugin.rbac.resource.api.initialization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.permission.ResourcePermissionCatalog;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ResourceModuleInitializerTest {
  private final ResourceRegistrationService service = mock(ResourceRegistrationService.class);
  private final ResourceModuleInitializer initializer =
      new ResourceModuleInitializer(
          new DefaultListableBeanFactory().getBeanProvider(ResourcePermissionCatalog.class));

  @Test
  void preparesBeforeWritingAndKeepsLogicalOwnerInsteadOfHostIdentity() throws Exception {
    var action = initializer.prepare(context("{\"resources\":[{\"code\":\"example.view\"}]}"));
    verifyNoInteractions(service);
    action.run();
    verify(service).sync(eq("example"), anySet());
  }

  @Test
  void rejectsConflictingOwnerDuplicateCodesAndMalformedChildrenBeforeWriting() {
    for (String json :
        new String[] {
          "{\"module\":\"other\",\"resources\":[]}",
          "{\"resources\":[{\"code\":\"one\",\"pluginId\":\"other\"}]}",
          "{\"resources\":[{\"code\":\"one\"},{\"code\":\"one\"}]}",
          "{\"resources\":[{\"code\":\"one\",\"children\":[{\"code\":\"one\"}]}]}",
          "{\"resources\":[{\"code\":\"one\",\"children\":{}}]}",
          "{\"resources\":[null]}",
          "{}",
          "{\"resources\":[]} {}"
        }) {
      assertThatThrownBy(() -> initializer.prepare(context(json))).isInstanceOf(Exception.class);
    }
    verifyNoInteractions(service);
  }

  private ModuleInitializationContext context(String json) {
    return new ModuleInitializationContext() {
      @Override
      public String moduleId() {
        return "example";
      }

      @Override
      public java.util.Optional<String> resourceOwner() {
        return java.util.Optional.of("example");
      }

      @Override
      public void claimResourceCodes(java.util.Set<String> codes) {
        org.assertj.core.api.Assertions.assertThat(codes).doesNotContainNull();
      }

      @Override
      public <T> T client(Class<T> contract) {
        return contract.cast(service);
      }

      @Override
      public byte[] resource(String path) {
        return json.getBytes(StandardCharsets.UTF_8);
      }
    };
  }
}
