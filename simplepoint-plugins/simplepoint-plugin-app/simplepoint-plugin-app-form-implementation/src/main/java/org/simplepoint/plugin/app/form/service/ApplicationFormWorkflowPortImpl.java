package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowBindingCheck;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowSubmissionCheck;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormWorkflowPort;
import org.simplepoint.plugin.app.form.configuration.ApplicationFormWorkflowRemoteProperties;
import org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies trusted engine calls against APP facts; no business payload is returned by this port.
 */
@Service
@RemoteProvider
@EnableConfigurationProperties(ApplicationFormWorkflowRemoteProperties.class)
@Transactional(readOnly = true)
public class ApplicationFormWorkflowPortImpl implements ApplicationFormWorkflowPort {
  private final EntityManager entityManager;
  private final FormDocumentCodec documents;
  private final FormSubmissionCodec submissions;
  private final Set<String> callers;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public ApplicationFormWorkflowPortImpl(
      EntityManager entityManager,
      FormDocumentCodec documents,
      FormSubmissionCodec submissions,
      ApplicationFormWorkflowRemoteProperties properties) {
    this.entityManager = entityManager;
    this.documents = documents;
    this.submissions = submissions;
    callers = Set.copyOf(properties.getAllowedCallers());
  }

  @Override
  public void validateBinding(FormWorkflowBindingCheck check) {
    requireCaller();
    Objects.requireNonNull(check, "check");
    var version =
        entityManager
            .createQuery(
                "from ApplicationFormVersionEntity version where version.tenantId = :tenant "
                    + "and version.formId = :form and version.versionNumber = :version",
                ApplicationFormVersionEntity.class)
            .setParameter("tenant", check.tenantId())
            .setParameter("form", check.formId())
            .setParameter("version", check.version())
            .getResultStream()
            .findFirst()
            .orElseThrow(() -> invalid("The bound form version is unavailable"));
    if (version.getPhysicalModelVersion() == null || version.getStorageChecksum() == null) {
      throw invalid("Workflow can bind only a published physical form version");
    }
    var publicationCount =
        entityManager
            .createQuery(
                "select count(p) from ApplicationFormPublicationEntity p where p.tenantId = :tenant"
                    + " and p.formId = :form and p.formVersion = :version and p.status in :states",
                Long.class)
            .setParameter("tenant", check.tenantId())
            .setParameter("form", check.formId())
            .setParameter("version", check.version())
            .setParameter(
                "states",
                List.of(
                    org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus
                        .ACTIVE,
                    org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus
                        .RETIRED))
            .getSingleResult();
    if (publicationCount == 0) {
      throw invalid("The form version has not completed publication");
    }
    var document = documents.decode(version.getDocumentJson());
    if (check.sceneId() != null
        && document.scenes().stream()
            .noneMatch(
                scene ->
                    scene.sceneId().equals(check.sceneId())
                        && (!"start".equals(check.stage())
                            || scene.type()
                                == org.simplepoint.plugin.app.form.api.model.FormSceneType.MAIN))) {
      throw invalid("The form binding references an unavailable scene");
    }
    var fields =
        document.fields().stream()
            .collect(Collectors.toMap(FormFieldDefinition::fieldId, Function.identity()));
    check
        .fieldVariableTypes()
        .forEach(
            (id, type) -> {
              var field = fields.get(id);
              if (field == null
                  || !Set.of(FormStorageMode.STORED, FormStorageMode.REFERENCE)
                      .contains(field.storageMode())
                  || !FormSubmissionMapping.compatible(field.type(), type)) {
                throw invalid("Incompatible form field mapping: " + id);
              }
            });
  }

  @Override
  public void validateSubmission(FormWorkflowSubmissionCheck check) {
    requireCaller();
    Objects.requireNonNull(check, "check");
    String id = check.businessKey().substring("app-form:".length());
    var row = entityManager.find(ApplicationFormSubmissionEntity.class, id);
    if (row == null
        || !row.getTenantId().equals(check.tenantId())
        || !row.getFormId().equals(check.formId())
        || row.getFormVersion() != check.formVersion()
        || !row.getDefinitionId().equals(check.definitionId())
        || row.getDefinitionVersion() != check.definitionVersion()
        || row.getSnapshotJson() == null
        || !Set.of("DATA_COMMITTED", "STARTED", "TERMINAL").contains(row.getStep())) {
      throw invalid(
          "Workflow must reference a committed submission of this exact form and process version");
    }
    var accepted = submissions.read(row.getIntentJson(), AcceptedFormSubmission.class);
    if (!check.administrator() && !check.resources().contains(accepted.form().resourceCode())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "The workflow actor cannot access this business form page");
    }
    final var snapshot = submissions.read(row.getSnapshotJson(), ApplicationFormRecordView.class);
    var contract = accepted.workflow().formContract();
    var binding =
        "start".equals(check.stage())
            ? contract.startBinding()
            : contract
                .taskBindings()
                .getOrDefault(check.taskDefinitionKey(), contract.startBinding());
    if (binding == null
        || !FormSubmissionMapping.PROVIDER.equals(binding.provider())
        || !binding.formId().equals(check.formId())
        || binding.version() != check.formVersion()) {
      throw invalid("Workflow node does not match the accepted form binding");
    }
    if ("start".equals(check.stage()) && !row.getActorId().equals(check.actorId())
        || "task".equals(check.stage())
            && row.getProcessInstanceId() != null
            && !row.getProcessInstanceId().equals(check.processInstanceId())) {
      throw invalid("Workflow actor or instance does not own this submission");
    }
    var runtime = new RuntimeForm(check.definitionVersion(), contract, binding, Map.of());
    Map<String, Object> expected = new LinkedHashMap<>();
    if ("start".equals(check.stage())) {
      expected.putAll(accepted.workflow().values());
    }
    expected.putAll(
        FormSubmissionMapping.variables(
            accepted.form(),
            runtime,
            snapshot.values(),
            "start".equals(check.stage()) ? accepted.command().variables() : Map.of()));
    var types =
        contract.variables().stream()
            .collect(
                Collectors.toMap(
                    WorkflowFormContract.Variable::name, WorkflowFormContract.Variable::type));
    if ("start".equals(check.stage()) && !expected.keySet().equals(check.variables().keySet())) {
      throw invalid("Workflow variables do not match the accepted submission");
    }
    expected.forEach(
        (name, value) -> {
          if (("start".equals(check.stage()) || check.variables().containsKey(name))
              && !equivalent(value, check.variables().get(name), types.get(name))) {
            throw invalid(
                "Workflow variables cannot override the committed form snapshot: " + name);
          }
        });
  }

  private static boolean equivalent(Object expected, Object actual, String type) {
    if (expected == null || actual == null) {
      return expected == actual;
    }
    if ("number".equals(type) || "decimal".equals(type)) {
      try {
        return new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString()))
            == 0;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return Objects.equals(expected, actual);
  }

  private void requireCaller() {
    if (!ServiceClientIdentity.allowed(
        SecurityContextHolder.getContext().getAuthentication(), callers)) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "Caller cannot invoke the APP workflow contract");
    }
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }
}
