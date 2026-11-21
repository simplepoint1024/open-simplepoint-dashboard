package org.simplepoint.plugin.dna.runtime.service.record;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelAccessService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordCommandService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordInspectionService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordWritePolicyService;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.repository.RecordCreateReceiptRepository;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalModelAccessServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalModelResourceStateService;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalog;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Integration-only assembly in the owning package; production internals remain non-public. */
public final class DnaFormRecordTestServices {
  public final PhysicalModelAccessService access;
  public final RecordCommandService commands;
  public final RecordTransactionService transactions;
  public final RecordQueryService queries;
  public final RecordInspectionService inspections;
  public final RecordWritePolicyService policies;
  public final OwnedRecordService owned;

  /** Uses actual module-bound control persistence and the native target backend. */
  public DnaFormRecordTestServices(
      EntityManager em,
      PlatformTransactionManager manager,
      PhysicalRelationCatalog relations,
      RecordMaintenanceControl maintenance,
      RuntimeCalciteRecordBackend backend,
      ObjectMapper mapper,
      DnaRuntimeAuditPublisher publisher) {
    var repositories = new JpaRepositoryFactory(em);
    var models = repositories.getRepository(PhysicalModelRepository.class);
    var bindings = repositories.getRepository(PhysicalModelBindingRepository.class);
    var versions = repositories.getRepository(PhysicalModelVersionRepository.class);
    var resources = repositories.getRepository(PhysicalModelResourceBindingRepository.class);
    var codec = new PhysicalSchemaCodec(mapper);
    var resolver =
        new RuntimeRecordModelResolver(
            models,
            bindings,
            versions,
            codec,
            new PhysicalReferenceResolver(models, bindings, versions, codec));
    var request = new RuntimeRequestContext();
    var resourceFence = new RecordResourceFence(em, manager, relations);
    var permission = new RuntimeRecordAccessPolicy(resources);
    permission.setResourceFence(resourceFence);
    var sql = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var calculationWrites = new RecordCalculationWriteSupport(resolver, codec, sql);
    var calculationReads = new RecordCalculationAccess(calculationWrites, permission);
    backend.setResourceFence(resourceFence);
    backend.setReferenceAdmission(
        new RecordReferenceAdmission(request, resolver, permission, calculationReads, sql, codec),
        sql);
    var admission = new RecordAdmissionController(new DnaRuntimeExecutionProperties());
    var audit = new RecordAuditSupport(publisher);
    var observer = mock(RecordExecutionObserver.class);
    var fingerprints = new RecordRequestFingerprint(mapper);
    var transactionService =
        new RecordTransactionServiceImpl(
            request,
            resolver,
            permission,
            sql,
            backend,
            audit,
            admission,
            observer,
            fingerprints,
            calculationWrites);
    transactions = transactionService;
    var receipts =
        transactional(
            new RecordCreateReceiptStore(
                repositories.getRepository(RecordCreateReceiptRepository.class)),
            manager);
    commands =
        new RecordCommandServiceImpl(
            new RecordCreateCoordinator(transactionService, receipts, fingerprints),
            transactionService);
    queries =
        new RecordQueryServiceImpl(
            request,
            resolver,
            permission,
            sql,
            backend,
            audit,
            admission,
            observer,
            calculationReads);
    inspections =
        new RecordInspectionServiceImpl(
            request, resolver, sql, backend, admission, audit, observer);
    var coordinator =
        new RecordPublicationCoordinator(
            new RecordPublicationControlFence(maintenance, manager, relations),
            new RecordPublicationMetadata(em, resolver, codec),
            backend,
            new RecordPublicationBatchExecutor(codec, sql),
            fingerprints);
    policies =
        new RecordWritePolicyServiceImpl(
            request,
            resolver,
            sql,
            backend,
            fingerprints,
            admission,
            audit,
            observer,
            new RecordCalculationPublicationAdapter(coordinator, fingerprints));
    access =
        new PhysicalModelAccessServiceImpl(
            transactional(
                new PhysicalModelResourceStateService(em, request, models, resources, maintenance),
                manager));
    owned =
        new OwnedRecordService(
            request,
            resolver,
            permission,
            sql,
            backend,
            fingerprints,
            admission,
            audit,
            observer,
            calculationWrites,
            calculationReads,
            codec);
  }

  @SuppressWarnings("unchecked")
  private static <T> T transactional(T service, PlatformTransactionManager manager) {
    var proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(manager);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    proxy.addAdvice(interceptor);
    return (T) proxy.getProxy();
  }
}
