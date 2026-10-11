package org.simplepoint.plugin.storage.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.schema.AnnotatedTableSchemaProvider;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;

/** The table's actions use the same exact authorities as the controller, not view-as-write inference. */
class StorageActionsTest {
  private Set<String> keys(Class<?> entity, AuthorizationContext context) {
    return new AnnotatedTableSchemaProvider(null).buttonSchemas(entity, context).stream()
        .map(action -> (String) action.get("key")).collect(java.util.stream.Collectors.toSet());
  }

  @Test
  void readonlyViewsNeverExposeMutationActions() {
    var context = new AuthorizationContext();
    context.setResources(Set.of("storage.providers.view", "storage.quotas.view", "storage.objects.view"));
    assertThat(keys(ObjectStorageProviderConfig.class, context)).isEmpty();
    assertThat(keys(ObjectStorageTenantQuota.class, context)).isEmpty();
    assertThat(keys(ObjectStorageObject.class, context)).containsExactlyInAnyOrder("view", "download");
  }

  @Test
  void independentAuthoritiesExposeOnlyTheirOwnActions() {
    var context = new AuthorizationContext();
    context.setResources(Set.of("storage.providers.test", "storage.quotas.edit", "storage.objects.create"));
    assertThat(keys(ObjectStorageProviderConfig.class, context)).containsExactly("test");
    assertThat(keys(ObjectStorageTenantQuota.class, context)).containsExactly("edit");
    assertThat(keys(ObjectStorageObject.class, context)).containsExactly("upload");
  }

  @Test
  void administratorsHaveAllExplicitActionsWithoutGrantingObjectMetadataEdits() {
    var context = new AuthorizationContext();
    context.setIsAdministrator(true);
    assertThat(keys(ObjectStorageProviderConfig.class, context)).containsExactlyInAnyOrder("add", "edit", "delete", "test");
    assertThat(keys(ObjectStorageTenantQuota.class, context)).containsExactlyInAnyOrder("add", "edit", "delete");
    assertThat(keys(ObjectStorageObject.class, context)).containsExactlyInAnyOrder("upload", "view", "download", "delete");
  }
}
