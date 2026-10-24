package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDefaults;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModuleDictionaryPlan;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModuleResourcePlan;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

/** Architecture checks on actual domain artifacts, without bootstrapping databases or services. */
class PlatformDeploymentPlanTest {

  private static final List<String> COMMON =
      List.of(
          "authorization-http",
          "common-ui",
          "identity-http",
          "i18n-http",
          "oidc-http",
          "storage-http",
          "personal-http",
          "notification-http",
          "bootstrap-registry");
  private static final List<String> AI = List.of("ai-http");
  private static final List<String> AUDIT = List.of("auditing-http");

  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @Test
  void everyOwnedControllerBelongsToAnExplicitHttpAdapter() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(
        new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
    for (var module : catalog.modules()) {
      for (String root : module.packages()) {
        var controllers = scanner.findCandidateComponents(root);
        if (!controllers.isEmpty()) {
          assertTrue(
              module
                  .configuration()
                  .isAnnotationPresent(org.simplepoint.platform.composition.ModuleHttp.class),
              module.id() + " owns undeclared HTTP endpoints: " + controllers);
        }
      }
    }
  }

  @Test
  void optionalCommonHttpAdaptersOwnDisjointSubtreesAndNoDomainControllers() throws Exception {
    var domain = catalog.plan(List.of("identity", "oidc", "i18n", "notification"));
    assertTrue(ModuleHttpPlan.from(domain).routes().isEmpty());
    for (String controller :
        List.of(
            "org.simplepoint.plugin.rbac.core.rest.controller.UsersController",
            "org.simplepoint.plugin.rbac.resource.rest.endpoint.ResourcesController",
            "org.simplepoint.plugin.rbac.tenant.rest.controller.TenantController",
            "org.simplepoint.plugin.oidc.rest.controller.OidcClientController",
            "org.simplepoint.plugin.i18n.rest.controller.I18nLanguagesController",
            "org.simplepoint.plugin.personal.rest.PersonalController",
            "org.simplepoint.plugin.notification.rest.controller.NotificationInboxController",
            "org.simplepoint.plugin.notification.rest.push.NotificationSseBroker")) {
      assertTrue(domain.ownerOf(Class.forName(controller)).isEmpty());
    }
    var adapter =
        catalog.plan(
            List.of(
                "identity-http", "oidc-http", "i18n-http", "personal-http", "notification-http"));
    var paths = ModuleHttpPlan.from(adapter).routes();
    assertEquals(
        Set.of("/common/oidc/clients", "/common/oidc/external-providers"), paths.get("oidc-http"));
    assertEquals(6, paths.get("i18n-http").size());
    assertEquals(Set.of("/common/personal"), paths.get("personal-http"));
    assertEquals(
        Set.of("/common/notifications", "/common/system/notifications"),
        paths.get("notification-http"));
    assertTrue(
        paths
            .get("identity-http")
            .containsAll(
                Set.of(
                    "/common/users",
                    "/common/tenants",
                    "/common/platform/tenants",
                    "/common/resources",
                    "/common/ops/microapps")));
    var split =
        catalog.deploy(
            Map.of(
                "oidc-host", List.of("oidc-http"),
                "inbox-host", List.of("personal-http", "notification-http"),
                "audit-host", List.of("audit-logging"),
                "identity-host", List.of("identity-http", "bootstrap-registry", "i18n-http")));
    assertEquals("inbox-host", split.httpRoutes().get("/common/notifications"));
    assertEquals("inbox-host", split.httpRoutes().get("/common/personal"));
    assertEquals("oidc-host", split.httpRoutes().get("/common/oidc/clients"));
    assertEquals("identity-host", split.httpRoutes().get("/common/i18n/languages"));
  }

  @Test
  void actualDomainsHaveEquivalentCapabilitySetsAcrossThreeTopologies() {
    var distributed =
        catalog.deploy(
            Map.of(
                "common",
                COMMON,
                "ai",
                AI,
                "auditing",
                AUDIT,
                "dna",
                List.of("dna-http"),
                "app",
                List.of("app-http"),
                "workflow",
                List.of("workflow-http"),
                "mcp-gateway",
                List.of("mcp-gateway")));
    var all = catalog.modules().stream().map(ModuleDescriptor::id).toList();
    var standalone = catalog.deploy(Map.of("standalone", all));
    final var mixed =
        catalog.deploy(
            Map.of(
                "common",
                COMMON,
                "ai",
                AI,
                "auditing",
                AUDIT,
                "business",
                List.of("app-http", "workflow-http", "dna-http", "mcp-gateway")));

    var dictionaries = ModuleDictionaryPlan.declarations(distributed);
    assertEquals(Set.of("oidc", "notification"), dictionaries.keySet());
    assertEquals(dictionaries, ModuleDictionaryPlan.declarations(standalone));
    assertEquals(dictionaries, ModuleDictionaryPlan.declarations(mixed));
    assertEquals(8, dictionaries.values().stream().mapToInt(d -> d.dictionaries().size()).sum());
    assertEquals(
        27,
        dictionaries.values().stream()
            .flatMap(d -> d.dictionaries().stream())
            .mapToInt(d -> d.items().size())
            .sum());
    assertEquals(35, all.size());
    assertEquals(
        all.size(),
        distributed.hosts().values().stream().mapToInt(plan -> plan.modules().size()).sum());
    assertEquals(distributed.capabilityHosts().keySet(), standalone.capabilityHosts().keySet());
    assertEquals(distributed.capabilityHosts().keySet(), mixed.capabilityHosts().keySet());
    assertEquals(Map.of(), standalone.routesFor("standalone"));
    assertEquals(
        Map.of(
            "common",
            "simplepoint-module-common-ui",
            "workflow",
            "simplepoint-module-workflow-http",
            "app",
            "simplepoint-module-app-http",
            "dna",
            "simplepoint-module-dna-http",
            "auditing",
            "simplepoint-module-auditing-http",
            "ai",
            "simplepoint-module-ai-http"),
        ModuleResourcePlan.registrationClients(distributed, Map.of()));
    assertEquals(
        ModuleResourcePlan.registrationClients(distributed, Map.of()),
        ModuleResourcePlan.registrationClients(standalone, Map.of()));
    assertEquals(
        ModuleResourcePlan.registrationClients(distributed, Map.of()),
        ModuleResourcePlan.registrationClients(mixed, Map.of()));
    assertEquals("dna", distributed.routesFor("app").get("dna.form-runtime:1"));
    assertEquals("common", mixed.routesFor("business").get("saas.managed-resource:1"));
    assertTrue(
        mixed.routesFor("business").keySet().stream()
            .noneMatch(key -> key.startsWith("dna.") || key.startsWith("workflow.")));
  }

  @Test
  void localClosureIncludesPublicationCacheBridgeAndManagedRuntime() {
    assertEquals(
        List.of("dna-core", "dna-federation", "dna-runtime"),
        catalog.plan(List.of("dna-runtime")).modules().stream().map(ModuleDescriptor::id).toList());
    assertEquals(
        List.of("ai-core", "ai-runtime", "ai-mcp", "ai-catalog"),
        catalog.plan(List.of("ai-catalog")).modules().stream().map(ModuleDescriptor::id).toList());
    assertEquals(
        List.of("personal", "notification"),
        catalog.plan(List.of("notification")).modules().stream()
            .map(ModuleDescriptor::id)
            .toList());
  }

  @Test
  void declarationsCoverEveryPackagedRemoteContractAndOneRealComponentProvider() throws Exception {
    var environment = new StandardEnvironment();
    var all = catalog.plan(catalog.modules().stream().map(ModuleDescriptor::id).toList());
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "module-defaults",
                new java.util.HashMap<>(ModuleDefaults.load(all, getClass().getClassLoader()))));
    var declared = new HashSet<Class<?>>();
    for (var module : catalog.modules()) {
      for (var contract : module.provides().values()) {
        assertTrue(
            declared.add(contract), "Duplicate capability declaration: " + contract.getName());
        var assignable = new AssignableTypeFilter(contract);
        var component = new AnnotationTypeFilter(Component.class);
        var providers = new ClassPathScanningCandidateComponentProvider(false, environment);
        providers.addIncludeFilter(
            (reader, factory) ->
                assignable.match(reader, factory) && component.match(reader, factory));
        Set<String> implementations = new HashSet<>();
        for (String root : module.packages()) {
          providers
              .findCandidateComponents(root)
              .forEach(bean -> implementations.add(bean.getBeanClassName()));
        }
        assertEquals(
            1,
            implementations.size(),
            module.id() + " -> " + contract.getName() + ": " + implementations);
      }
    }
    var scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(final AnnotatedBeanDefinition definition) {
            return definition.getMetadata().isInterface()
                && definition.getMetadata().isIndependent();
          }
        };
    scanner.addIncludeFilter(new AnnotationTypeFilter(RemoteContract.class));
    var actual = new HashSet<Class<?>>();
    for (var bean : scanner.findCandidateComponents("org.simplepoint")) {
      Class<?> contract =
          Class.forName(bean.getBeanClassName(), false, getClass().getClassLoader());
      assertTrue(
          contract.getMethods().length > 0, "Empty local marker exported as RPC: " + contract);
      actual.add(contract);
    }
    assertEquals(actual, declared);
    assertEquals(21, actual.size());
  }

  @Test
  void splittingAnActualLocalDependencyAcrossServicesIsRejected() {
    var exception =
        assertThrows(
            ModuleCompositionException.class,
            () ->
                catalog.deploy(
                    Map.of(
                        "runtime",
                        List.of("dna-runtime"),
                        "federation",
                        List.of("dna-federation"))));

    assertEquals("DUPLICATE_MODULE_HOST", exception.code());
  }

  @Test
  void domainAssembliesDoNotImportRunnableServiceApplications() {
    for (String entry :
        List.of(
            "org.simplepoint.common.server.Common",
            "org.simplepoint.app.server.ApplicationWorkbenchApplication",
            "org.simplepoint.workflow.server.WorkflowApplication",
            "org.simplepoint.dna.server.DnaApplication")) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName(entry, false, getClass().getClassLoader()));
    }
    assertEquals(
        Set.of(
            "identity",
            "identity-http",
            "i18n",
            "i18n-http",
            "oidc",
            "oidc-http",
            "storage",
            "storage-http",
            "common-ui",
            "authorization-http",
            "authorization-audit",
            "personal",
            "personal-http",
            "notification",
            "notification-http",
            "bootstrap-registry"),
        catalog.plan(COMMON).modules().stream()
            .map(ModuleDescriptor::id)
            .collect(Collectors.toSet()));
  }
}
