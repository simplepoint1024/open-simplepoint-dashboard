package org.simplepoint.platform.composition.verification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.service.RuntimeMetadataCacheInvalidator;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimePublicationProperties;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationStepRepository;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalModelServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPlanServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService;
import org.simplepoint.plugin.dna.runtime.service.record.DnaFormRecordTestServices;
import org.simplepoint.plugin.dna.runtime.service.record.RecordMaintenanceControl;
import org.simplepoint.plugin.dna.runtime.service.record.RecordWriteFencePreparation;
import org.simplepoint.plugin.dna.runtime.service.record.RuntimeCalciteRecordBackend;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalog;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalogBoundary;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaFormRuntimeCallerGuard;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaFormRuntimeServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalModelOwnershipRegistry;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.SchemaPlanCodec;
import org.simplepoint.plugin.dna.runtime.service.support.DefaultPhysicalTableNamingStrategy;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaValidator;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** APP schema coordination over real DNA transactions and migrated modular persistence. */
final class FormDnaPublicationFixture implements AutoCloseable {
  final FormRelationTestDatabase control;
  final FormRelationTestDatabase target;
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final DnaFormRuntimeServiceImpl dna;
  final DnaFormRecordTestServices recordServices;
  final FormDnaSchemaPublicationCoordinator publications;
  final EntityManager appEntities;
  final PlatformTransactionManager appTransactions;
  final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

  FormDnaPublicationFixture(boolean postgres) throws Exception {
    control = new FormRelationTestDatabase(true);
    FormRelationTestDatabase physical = null;
    try {
      physical = new FormRelationTestDatabase(postgres);
      target = physical;
      var modules =
          ModuleCatalog.discover(getClass().getClassLoader())
              .plan(List.of("dna-runtime", "app-form"));
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      new ModuleMigrationEngine()
          .migrate(
              ModuleMigrationPlan.discover(modules, persistence.bindings()),
              Map.of("default", control.source));
      context.registerBean(ModulePlan.class, () -> modules);
      context.registerBean("dataSource", DataSource.class, () -> control.source);
      context.register(Persistence.class);
      context.refresh();
      var appBinding = persistence.bindings().require("app-form");
      appEntities = context.getBean(appBinding.entityManager(), EntityManager.class);
      appTransactions =
          context.getBean(appBinding.transactionManager(), PlatformTransactionManager.class);
      var binding = persistence.bindings().require("dna-runtime");
      var em = context.getBean(binding.entityManager(), EntityManager.class);
      var transactions =
          context.getBean(binding.transactionManager(), PlatformTransactionManager.class);
      var repositories = new JpaRepositoryFactory(em);
      var models = repositories.getRepository(PhysicalModelRepository.class);
      var bindings = repositories.getRepository(PhysicalModelBindingRepository.class);
      var versions = repositories.getRepository(PhysicalModelVersionRepository.class);
      var codec = new PhysicalSchemaCodec(mapper);
      var snapshots = new PhysicalSchemaSnapshotCodec(mapper);
      var provider = provider(snapshots);
      var backend = new JdbcPhysicalSchemaBackend(provider, snapshots);
      var records = new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine());
      var maintenance =
          new RecordMaintenanceControl(em, models, bindings, versions, codec, records);
      var resolver = new PhysicalReferenceResolver(models, bindings, versions, codec);
      var relations =
          new PhysicalRelationCatalog(
              em, new PhysicalRelationCatalogBoundary(em), versions, bindings, codec, resolver);
      var request = new RuntimeRequestContext();
      var audit = mock(DnaRuntimeAuditPublisher.class);
      var physicalModels =
          transactional(
              new PhysicalModelServiceImpl(
                  models,
                  bindings,
                  versions,
                  placement ->
                      new PhysicalBindingTarget(
                          "relation-test",
                          target.catalog,
                          target.schema(),
                          PhysicalIsolationMode.SHARED_DATABASE),
                  new DefaultPhysicalTableNamingStrategy(),
                  codec,
                  new PhysicalSchemaValidator(new StandardLogicalValueConverter()),
                  request,
                  audit,
                  maintenance,
                  relations,
                  new PhysicalModelOwnershipRegistry(provider)),
              transactions);
      var state =
          transactional(
              new SchemaPublicationStateService(
                  models,
                  bindings,
                  versions,
                  repositories.getRepository(SchemaMigrationRepository.class),
                  repositories.getRepository(SchemaMigrationStepRepository.class),
                  codec,
                  new SchemaPlanCodec(mapper, snapshots),
                  resolver,
                  audit,
                  maintenance,
                  relations),
              transactions);
      var publish =
          new SchemaPublicationServiceImpl(
              request,
              state,
              backend,
              mock(RuntimeMetadataCacheInvalidator.class),
              new DnaRuntimePublicationProperties(),
              new RecordJournalProvisioner(backend),
              new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(backend), records));
      var properties = new DnaFormRuntimeRemoteProperties();
      properties.setAllowedCallers(Set.of("app-form-test"));
      recordServices =
          new DnaFormRecordTestServices(
              em, transactions, relations, maintenance, records, mapper, audit);
      dna =
          new DnaFormRuntimeServiceImpl(
              physicalModels,
              new SchemaPlanServiceImpl(request, state, backend),
              publish,
              recordServices.access,
              recordServices.queries,
              recordServices.commands,
              recordServices.transactions,
              recordServices.inspections,
              recordServices.policies,
              new DnaFormRuntimeCallerGuard(properties));
      publications = new FormDnaSchemaPublicationCoordinator(dna);
      authenticate("app-form-test");
    } catch (Exception | Error failure) {
      context.close();
      if (physical != null) {
        try {
          physical.close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      try {
        control.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  static DnaInvocationContext actor(String tenant) {
    return new DnaInvocationContext(
        tenant, "author", null, null, true, Set.of(), "ALL", Set.of(), false, List.of(), Map.of());
  }

  static void authenticate(String client) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                new DefaultOAuth2AuthenticatedPrincipal(
                    client,
                    Map.of("client_id", client, "grant_type", "client_credentials"),
                    List.of()),
                null,
                "SCOPE_service-router.invoke"));
  }

  private ManagedRuntimeDataSourceProvider provider(PhysicalSchemaSnapshotCodec snapshots)
      throws Exception {
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    JdbcDatabaseDialect core =
        target.postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    RuntimeSchemaDialect dialect =
        target.postgres
            ? new PostgresqlRuntimeSchemaDialect(snapshots)
            : new MysqlRuntimeSchemaDialect(snapshots);
    try (var connection = target.source.getConnection()) {
      UUID identity = UUID.randomUUID();
      PhysicalOwnershipInstaller.install(connection, target.namespace, identity);
      PhysicalOwnershipInstaller.activateFresh(connection, target.namespace, identity);
      var metadata = connection.getMetaData();
      var database =
          new DatabaseIdentity(
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              metadata.getDatabaseMajorVersion(),
              metadata.getDatabaseMinorVersion());
      var support =
          new JdbcDatabaseDialect.SupportContext(
              core.code(),
              metadata.getDriverName(),
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              target.catalog,
              target.schema(),
              metadata.getIdentifierQuoteString(),
              Map.of());
      when(provider.open(any()))
          .thenReturn(
              new ManagedRuntimeDataSource(
                  new SimpleDataSource(target.source),
                  false,
                  support,
                  core,
                  dialect,
                  database,
                  target.catalog,
                  target.schema()));
    }
    return provider;
  }

  @SuppressWarnings("unchecked")
  static <T> T transactional(T service, PlatformTransactionManager manager) {
    var proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(manager);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    proxy.addAdvice(interceptor);
    return (T) proxy.getProxy();
  }

  @Override
  public void close() throws Exception {
    SecurityContextHolder.clearContext();
    try {
      context.close();
    } finally {
      try {
        target.close();
      } finally {
        control.close();
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
