package org.simplepoint.platform.composition.verification;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelReads;
import org.simplepoint.plugin.workflow.service.WorkflowInboxQueries;
import org.simplepoint.plugin.workflow.service.WorkflowMatterQueries;

/** Real migrated Flowable history with independently bound identity and workflow persistence. */
final class WorkflowQueryFixture implements AutoCloseable {
  private static final String BPMN =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
        xmlns:flowable="http://flowable.org/bpmn" targetNamespace="composition">
        <process id="labels_approval" isExecutable="true">
          <startEvent id="start"/>
          <userTask id="approve" name="审核" flowable:assignee="u0000"/>
          <endEvent id="end"/>
          <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
          <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
        </process>
      </definitions>
      """;
  final MemberLabelProjectionFixture labels;
  final ProcessEngine engine;
  final EntityManager em;

  WorkflowQueryFixture(boolean split) {
    labels = new MemberLabelProjectionFixture(split, 3);
    ProcessEngine started = null;
    try {
      var binding = labels.context.getBean(ModuleStorageBindings.class).require("workflow");
      var configuration = new SpringProcessEngineConfiguration();
      configuration.setEngineName("query-" + labels.workflow.schema());
      configuration.setDataSource(labels.context.getBean(binding.dataSource(), DataSource.class));
      configuration.setTransactionManager(labels.workflowTransactions);
      configuration.setDatabaseSchema(split ? labels.workflow.schema() : labels.identity.schema());
      configuration.setDatabaseSchemaUpdate("false");
      configuration.setAsyncExecutorActivate(false);
      configuration.setHistory("audit");
      started = configuration.buildProcessEngine();
      engine = started;
      em = labels.context.getBean(binding.entityManager(), EntityManager.class);
      var deployment =
          engine
              .getRepositoryService()
              .createDeployment()
              .tenantId("tenant")
              .addString("labels.bpmn20.xml", BPMN)
              .deploy();
      var definition =
          engine
              .getRepositoryService()
              .createProcessDefinitionQuery()
              .deploymentId(deployment.getId())
              .singleResult();
      labels.jdbc.update(
          """
          insert into simpoint_workflow_definitions
          (id,tenant_id,definition_key,name,status,latest_version,draft_xml,created_by,created_at,updated_at)
          values ('definition','tenant','labels_approval','审批','PUBLISHED',1,?,'u0000',now(),now())
          """,
          BPMN);
      labels.jdbc.update(
          """
          insert into simpoint_workflow_definition_versions
          (id,definition_id,tenant_id,version_no,bpmn_xml,checksum,flowable_deployment_id,
           flowable_process_definition_id,published_by,published_at)
          values ('version','definition','tenant',1,?,'checksum',?,?,'u0000',now())
          """,
          BPMN,
          deployment.getId(),
          definition.getId());
      authorize("u0000", "tenant", true);
    } catch (RuntimeException | Error failure) {
      if (started != null) {
        started.close();
      }
      labels.close();
      throw failure;
    }
  }

  String start(String user, String title) {
    var instance =
        engine
            .getRuntimeService()
            .startProcessInstanceByKeyAndTenantId("labels_approval", Map.of(), "tenant");
    labels.jdbc.update(
        """
        insert into simpoint_workflow_instances
        (process_instance_id,tenant_id,definition_id,definition_version_id,definition_key,
         definition_name,definition_version,title,starter_id,status,started_at)
        values (?,'tenant','definition','version','labels_approval','审批',1,?,?,'RUNNING',now())
        """,
        instance.getId(),
        title,
        user);
    return instance.getId();
  }

  WorkflowMemberLabelReads reader(MemberLabelDirectory source) {
    return new WorkflowMemberLabelReads(labels.store, labels.projection(source, 8, 10), source);
  }

  WorkflowInboxQueries inbox(MemberLabelDirectory source) {
    return new WorkflowInboxQueries(em, reader(source));
  }

  WorkflowMatterQueries matters(MemberLabelDirectory source) {
    return new WorkflowMatterQueries(em, reader(source));
  }

  static void authorize(String user, String tenant, boolean administrator) {
    var context = new AuthorizationContext();
    context.setUserId(user);
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant));
    context.setIsAdministrator(administrator);
    context.setResources(List.of());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @Override
  public void close() {
    try {
      engine.close();
    } finally {
      labels.close();
    }
  }
}
