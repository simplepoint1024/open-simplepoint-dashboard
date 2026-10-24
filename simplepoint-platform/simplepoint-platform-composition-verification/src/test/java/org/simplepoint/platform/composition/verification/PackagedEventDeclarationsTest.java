package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.composition.ModuleEventPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleDefaults;

class PackagedEventDeclarationsTest {
  @Test
  void auditOnlyStorageContributesOneSharedLedgerWithoutIdentityTables() {
    var modules =
        ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("authorization-audit"));
    var storage =
        org.simplepoint.data.jpa.composition.ModulePersistencePlan.discover(
            modules,
            new org.springframework.core.env.StandardEnvironment(),
            new org.springframework.core.io.DefaultResourceLoader());
    assertEquals(1, storage.stores().size());
    org.junit.jupiter.api.Assertions.assertTrue(storage.entityOwners().isEmpty());
    var migrations =
        org.simplepoint.data.migration.ModuleMigrationPlan.discover(modules, storage.bindings());
    assertEquals(
        List.of("platform-events"),
        migrations.schemas().stream().map(schema -> schema.module()).toList());
  }

  @Test
  void selectedAuthorizationReusesItsHistoricalAuditIdentityWithoutOwningBusinessTables() {
    var modules =
        ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("authorization-http"));
    var plan = ModuleEventPlan.from(modules);
    assertEquals("authorization", plan.channels().get("authorization-audit").identity());
    assertEquals(
        List.of(org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract.PUBLICATION),
        plan.channels().get("authorization-audit").publications());
    org.junit.jupiter.api.Assertions.assertFalse(plan.channels().containsKey("authorization-http"));
    org.junit.jupiter.api.Assertions.assertTrue(
        modules.modules().stream()
            .filter(module -> module.id().equals("authorization-http"))
            .findFirst()
            .orElseThrow()
            .requires()
            .containsKey("authorization-audit"));
  }

  @Test
  void actualDomainArtifactsKeepHistoricalEventIdentitiesAndDeclaredDefaultEnablement() {
    var catalog = ModuleCatalog.discover(getClass().getClassLoader());
    var modules =
        catalog.plan(List.of("app-form", "identity", "dna-runtime", "audit-logging", "workflow"));
    var plan = ModuleEventPlan.from(modules);
    assertEquals(
        Map.of(
            "app-form",
            "app",
            "identity",
            "common",
            "dna-runtime",
            "dna",
            "audit-logging",
            "auditing",
            "workflow",
            "workflow"),
        plan.channels().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().identity())));
    assertEquals(
        "true",
        ModuleDefaults.load(modules, getClass().getClassLoader())
            .get("simplepoint.events.enabled"));
    assertEquals(2, plan.channels().get("app-form").subscriptions().size());
    assertEquals(1, plan.channels().get("identity").subscriptions().size());
    assertEquals(1, plan.channels().get("dna-runtime").subscriptions().size());
    assertEquals(2, plan.channels().get("audit-logging").subscriptions().size());
    assertEquals(
        List.of(
            org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents.WORKFLOW_SUBSCRIPTION),
        plan.channels().get("workflow").subscriptions());
    org.junit.jupiter.api.Assertions.assertTrue(
        plan.channels()
            .get("identity")
            .publications()
            .contains(org.simplepoint.plugin.rbac.tenant.api.event.MemberLabelEvents.PUBLICATION));
    var identityOnly = ModuleEventPlan.from(catalog.plan(List.of("identity")));
    assertEquals(plan.channels().get("identity"), identityOnly.channels().get("identity"));
  }
}
