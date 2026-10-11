package org.simplepoint.plugin.dna.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.DnaRuntimeAuditEventEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationStepEntity;
import org.simplepoint.plugin.dna.runtime.repository.DnaRuntimeAuditEventRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.support.DefaultPhysicalTableNamingStrategy;
import org.simplepoint.plugin.dna.runtime.service.support.JpaDnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaValidator;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.dao.DataAccessResourceFailureException;

/** Exercises the registry against an actual relational schema and Hibernate session. */
class PhysicalModelServiceDatabaseTest {

  private SessionFactory factory;
  private Session session;
  private PhysicalModelServiceImpl service;
  private PhysicalModelRepository modelRepository;
  private PhysicalModelBindingRepository bindingRepository;
  private PhysicalModelVersionRepository versionRepository;
  private DnaRuntimeAuditEventRepository auditRepository;

  @BeforeEach
  void setUp() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:dna-runtime-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build();
    factory = new MetadataSources(registry)
        .addAnnotatedClass(PhysicalModelEntity.class)
        .addAnnotatedClass(PhysicalModelBindingEntity.class)
        .addAnnotatedClass(PhysicalModelVersionEntity.class)
        .addAnnotatedClass(PhysicalModelResourceBindingEntity.class)
        .addAnnotatedClass(DnaRuntimeAuditEventEntity.class)
        .addAnnotatedClass(SchemaMigrationEntity.class)
        .addAnnotatedClass(SchemaMigrationStepEntity.class)
        .buildMetadata()
        .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    configureRepositories();

    service = new PhysicalModelServiceImpl(
        modelRepository,
        bindingRepository,
        versionRepository,
        request -> new PhysicalBindingTarget(
            "forms-primary",
            null,
            "forms",
            PhysicalIsolationMode.SHARED_DATABASE
        ),
        new DefaultPhysicalTableNamingStrategy(),
        new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules()),
        new PhysicalSchemaValidator(
            new StandardLogicalValueConverter(new ObjectMapper().findAndRegisterModules())
        ),
        new RuntimeRequestContext(),
        new JpaDnaRuntimeAuditPublisher(auditRepository)
    );
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) {
        session.getTransaction().rollback();
      }
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void registersTenantScopedModelBindingVersionAndAuditAtomically() {
    authorize("tenant-a", "user-a");

    var created = service.register(registration("form-a", "name"));

    assertThat(created.status()).isEqualTo(PhysicalModelStatus.DRAFT);
    assertThat(created.latestVersion()).isEqualTo(1);
    assertThat(created.activeVersion()).isNull();
    assertThat(created.latestSchema().checksum()).hasSize(64);
    assertThat(count(PhysicalModelEntity.class)).isEqualTo(1);
    assertThat(count(PhysicalModelBindingEntity.class)).isEqualTo(1);
    assertThat(count(PhysicalModelVersionEntity.class)).isEqualTo(1);
    assertThat(count(DnaRuntimeAuditEventEntity.class)).isEqualTo(1);
    PhysicalModelBindingEntity binding = session.createQuery(
        "from PhysicalModelBindingEntity",
        PhysicalModelBindingEntity.class
    ).getSingleResult();
    assertThat(binding.getDataSourceId()).isEqualTo("forms-primary");
    assertThat(binding.getTableName()).hasSize(30).startsWith("spf_");
    DnaRuntimeAuditEventEntity audit = session.createQuery(
        "from DnaRuntimeAuditEventEntity",
        DnaRuntimeAuditEventEntity.class
    ).getSingleResult();
    assertThat(audit.getDataSourceId()).isEqualTo("forms-primary");
    assertThat(audit.getDurationMillis()).isNotNegative();
    assertThat(audit.getAffectedRows()).isNull();

    authorize("tenant-b", "user-b");
    assertCode(
        () -> service.get(created.id()),
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND
    );
  }

  @Test
  void preventsDuplicateFormRegistrationAndStaleVersionCreation() {
    authorize("tenant-a", "user-a");
    var created = service.register(registration("form-a", "name"));

    assertCode(
        () -> service.register(registration("form-a", "other_name")),
        DnaRuntimeErrorCode.PHYSICAL_MODEL_ALREADY_EXISTS
    );

    var secondVersion = service.createVersion(
        created.id(),
        new PhysicalModelVersionCommand(
            created.lockVersion(),
            schema("renamed_name")
        )
    );
    assertThat(secondVersion.version()).isEqualTo(2);
    assertThat(service.get(created.id()).lockVersion()).isEqualTo(1);
    assertThat(count(PhysicalModelVersionEntity.class)).isEqualTo(2);
    assertThat(count(DnaRuntimeAuditEventEntity.class)).isEqualTo(2);

    assertCode(
        () -> service.createVersion(
            created.id(),
            new PhysicalModelVersionCommand(created.lockVersion(), schema("third_name"))
        ),
        DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT
    );
  }

  @Test
  void translatesPersistenceFailuresWithoutExposingDatabaseMessages() {
    authorize("tenant-a", "user-a");
    when(modelRepository.existsActiveByForm("tenant-a", "app-a", "form-a"))
        .thenThrow(new DataAccessResourceFailureException("password=secret"));

    assertThatThrownBy(() -> service.register(registration("form-a", "name")))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception -> {
          assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED);
          assertThat(exception.getMessage()).doesNotContain("password=secret");
        });
  }

  private void configureRepositories() {
    modelRepository = mock(PhysicalModelRepository.class);
    bindingRepository = mock(PhysicalModelBindingRepository.class);
    versionRepository = mock(PhysicalModelVersionRepository.class);
    auditRepository = mock(DnaRuntimeAuditEventRepository.class);

    when(modelRepository.existsActiveByForm(anyString(), anyString(), anyString()))
        .thenAnswer(call -> countQuery(
            "select count(model) from PhysicalModelEntity model "
                + "where model.tenantId = :tenant and model.appId = :app "
                + "and model.formId = :form and model.deletedAt is null",
            Map.of(
                "tenant", call.getArgument(0),
                "app", call.getArgument(1),
                "form", call.getArgument(2)
            )
        ) > 0);
    when(bindingRepository.existsActiveTarget(
        anyString(),
        anyString(),
        anyString(),
        anyString()
    )).thenAnswer(call -> countQuery(
        "select count(binding) from PhysicalModelBindingEntity binding "
            + "where binding.dataSourceId = :source and binding.catalogName = :catalog "
            + "and binding.schemaName = :schema and binding.tableName = :table "
            + "and binding.deletedAt is null",
        Map.of(
            "source", call.getArgument(0),
            "catalog", call.getArgument(1),
            "schema", call.getArgument(2),
            "table", call.getArgument(3)
        )
    ) > 0);

    when(modelRepository.save(any(PhysicalModelEntity.class)))
        .thenAnswer(call -> persist(call.getArgument(0)));
    when(bindingRepository.save(any(PhysicalModelBindingEntity.class)))
        .thenAnswer(call -> persist(call.getArgument(0)));
    when(versionRepository.save(any(PhysicalModelVersionEntity.class)))
        .thenAnswer(call -> persist(call.getArgument(0)));
    when(auditRepository.save(any(DnaRuntimeAuditEventEntity.class)))
        .thenAnswer(call -> persist(call.getArgument(0)));
    doAnswer(call -> {
      session.flush();
      return null;
    }).when(modelRepository).flush();

    when(modelRepository.findActiveById(anyString(), anyString()))
        .thenAnswer(call -> findModel(call.getArgument(0), call.getArgument(1)));
    when(modelRepository.findActiveByIdForUpdate(anyString(), anyString()))
        .thenAnswer(call -> findModel(call.getArgument(0), call.getArgument(1)));
    when(bindingRepository.findActiveByModel(anyString(), anyString()))
        .thenAnswer(call -> unique(
            "from PhysicalModelBindingEntity binding "
                + "where binding.tenantId = :tenant and binding.physicalModelId = :model "
                + "and binding.deletedAt is null",
            PhysicalModelBindingEntity.class,
            Map.of("tenant", call.getArgument(0), "model", call.getArgument(1))
        ));
    when(versionRepository.findActiveVersion(anyString(), anyString(), anyInt()))
        .thenAnswer(call -> unique(
            "from PhysicalModelVersionEntity version "
                + "where version.tenantId = :tenant and version.physicalModelId = :model "
                + "and version.versionNumber = :version and version.deletedAt is null",
            PhysicalModelVersionEntity.class,
            Map.of(
                "tenant", call.getArgument(0),
                "model", call.getArgument(1),
                "version", call.getArgument(2)
            )
        ));
  }

  private Optional<PhysicalModelEntity> findModel(
      final String tenantId,
      final String modelId
  ) {
    return unique(
        "from PhysicalModelEntity model where model.tenantId = :tenant "
            + "and model.id = :model and model.deletedAt is null",
        PhysicalModelEntity.class,
        Map.of("tenant", tenantId, "model", modelId)
    );
  }

  private <T> Optional<T> unique(
      final String hql,
      final Class<T> type,
      final Map<String, Object> parameters
  ) {
    var query = session.createQuery(hql, type);
    parameters.forEach(query::setParameter);
    return query.uniqueResultOptional();
  }

  private long countQuery(final String hql, final Map<String, Object> parameters) {
    var query = session.createQuery(hql, Long.class);
    parameters.forEach(query::setParameter);
    return query.getSingleResult();
  }

  private long count(final Class<?> type) {
    session.flush();
    return session.createQuery(
        "select count(entity) from " + type.getSimpleName() + " entity",
        Long.class
    ).getSingleResult();
  }

  private <T> T persist(final T entity) {
    if (!session.contains(entity)) {
      session.persist(entity);
    }
    return entity;
  }

  private static PhysicalModelRegistrationCommand registration(
      final String formId,
      final String fieldCode
  ) {
    return new PhysicalModelRegistrationCommand(
        "app-a",
        formId,
        "Customer Form",
        "default",
        schema(fieldCode)
    );
  }

  private static PhysicalSchemaDefinition schema(final String fieldCode) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(new LogicalFieldDefinition(
            "field-1",
            fieldCode,
            "Name",
            LogicalFieldType.STRING,
            false,
            128,
            null,
            null,
            null
        )),
        List.of()
    );
  }

  private static void authorize(final String tenantId, final String actorId) {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId(actorId);
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static void assertCode(
      final Runnable operation,
      final DnaRuntimeErrorCode expected
  ) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(expected));
  }
}
