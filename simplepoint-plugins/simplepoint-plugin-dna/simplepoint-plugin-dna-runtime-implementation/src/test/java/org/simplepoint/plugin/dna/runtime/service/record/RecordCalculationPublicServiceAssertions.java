package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.snapshot;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.audit.*;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

/** Public service -> protocol adapter -> actual control metadata -> real target batches, after coordinator versions 1..3. */
final class RecordCalculationPublicServiceAssertions {
  private RecordCalculationPublicServiceAssertions() {}

  static void verify(Database db, RuntimeRecordModelResolver models, RecordPublicationCoordinator coordinator) throws Exception {
    var fixture = db.fixture();
    var fingerprints = new RecordRequestFingerprint(new ObjectMapper());
    var audits = mock(DnaRuntimeAuditPublisher.class);
    var observer = mock(RecordExecutionObserver.class);
    var service = new RecordWritePolicyServiceImpl(new RuntimeRequestContext(), models, fixture.sql, db.backend(), fingerprints,
        new RecordAdmissionController(new DnaRuntimeExecutionProperties()), new RecordAuditSupport(audits), observer,
        new RecordCalculationPublicationAdapter(coordinator, fingerprints));
    var command = command(fixture.intent.command(), 3, 4, false);
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> service.publish(command)).isInstanceOfSatisfying(DnaRuntimeException.class,
        e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
    assertThatThrownBy(() -> service.cancel(command)).isInstanceOfSatisfying(DnaRuntimeException.class,
        e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
    RecordMaintenanceControlDatabaseTest.authorize();
    var result = service.publish(command);
    assertThat(result.published()).isFalse();
    assertThat(result.version()).isEqualTo(3);
    assertThat(result.progress().phase()).isEqualTo(RecordWritePolicyPhase.STAGING);
    verifyNoInteractions(audits);
    for (int attempt = 0; !result.published() && attempt < 6; attempt++) result = service.publish(command);
    assertThat(result.published()).isTrue();
    assertThat(result.version()).isEqualTo(4);
    assertThat(result.progress()).isNull();
    var audit = ArgumentCaptor.forClass(DnaRuntimeAuditEvent.class);
    org.mockito.Mockito.verify(audits).publish(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED);
    assertThat(audit.getValue().outcome()).isEqualTo(DnaRuntimeAuditOutcome.SUCCEEDED);
    assertThat(service.cancel(command).cancelled()).isFalse();
    Map<String, List<Map<String, String>>> before;
    try (var connection = transaction(db)) { before = snapshot(db, connection); }
    var rejected = command(fixture.intent.command(), 4, 5, true);
    result = service.publish(rejected);
    for (int attempt = 0; result.violations().isEmpty() && attempt < 6; attempt++) result = service.publish(rejected);
    assertThat(result.published()).isFalse();
    assertThat(result.version()).isEqualTo(4);
    assertThat(result.progress().phase()).isEqualTo(RecordWritePolicyPhase.APPLYING);
    assertThat(result.progress().failureCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED);
    assertThat(result.violations().get("required").emptyCounts()).containsEntry("old_summary", 1L);
    var journal = new RecordPublicationStore();
    var entry = db.backend().publication(fixture.parent, List.of(fixture.child), fixture.intent.key(), 30_000,
        s -> journal.read(fixture.intent.key(), 5, s.read(journal.lookup(fixture.intent.key(), 5))));
    assertThat(result.progress()).isEqualTo(entry.checkpoint().progress());
    assertThat(new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(result), RecordWritePolicyPublicationView.class)).isEqualTo(result);
    clearInvocations(audits);
    var cancellation = service.cancel(rejected);
    assertThat(cancellation.progress().phase()).isEqualTo(RecordWritePolicyPhase.RESTORING);
    assertThat(cancellation.cancelled()).isFalse();
    assertThat(cancellation.version()).isEqualTo(4);
    verifyNoInteractions(audits);
    for (int attempt = 0; cancellation.progress() != null && attempt < 4; attempt++) cancellation = service.cancel(rejected);
    assertThat(cancellation.cancelled()).isTrue();
    assertThat(cancellation.version()).isEqualTo(5);
    assertThat(cancellation.targetFingerprint()).isEqualTo(fingerprints.cancellationOf(rejected));
    org.mockito.Mockito.verify(audits).publish(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_CANCELLED);
    try (var connection = transaction(db)) { assertThat(snapshot(db, connection)).isEqualTo(before); }
    var store = new RecordCalculationStore();
    var installed = db.backend().publication(fixture.parent, List.of(fixture.child), fixture.intent.key(), 30_000,
        s -> store.read(fixture.intent.key(), 5, s.read(store.lookup(fixture.intent.key(), 5))));
    assertThat(installed.revision()).isEqualTo(4);
    assertThat(installed.policy().definition()).isEqualTo(command.calculation());
    assertThatThrownBy(() -> service.publish(rejected)).isInstanceOfSatisfying(DnaRuntimeException.class,
        e -> assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
  }

  private static RecordWritePolicyPublicationCommand command(RecordWritePolicyPublicationCommand base, long expected, long target, boolean reject) {
    var formulas = new LinkedHashMap<>(base.calculation().formulas());
    formulas.put("amount", "600.25");
    if (reject) formulas.put("text", "\"\"");
    Map<String, RecordCompletenessQuery> checks = reject ? Map.of("required", new RecordCompletenessQuery(base.physicalModelId(), base.appId(),
        base.formId(), base.schemaReference(), List.of("old_summary"), null)) : Map.of();
    return new RecordWritePolicyPublicationCommand(base.physicalModelId(), base.appId(), base.formId(), base.schemaReference(), expected, target,
        "public-service-" + target, base.policyChecksum(), checks, base.timeoutMs(), Map.of(),
        new RecordCalculationDefinition("1", formulas, base.calculation().details()));
  }
}
