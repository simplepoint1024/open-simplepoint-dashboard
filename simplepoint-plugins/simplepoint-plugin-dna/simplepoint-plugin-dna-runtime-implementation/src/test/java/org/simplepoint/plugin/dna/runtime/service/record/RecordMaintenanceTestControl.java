package org.simplepoint.plugin.dna.runtime.service.record;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.LinkedHashMap;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;

/** Isolates existing metadata-only fixtures; RecordMaintenanceControlDatabaseTest uses the real guard. */
public final class RecordMaintenanceTestControl {
  private RecordMaintenanceTestControl() { }

  public static RecordMaintenanceControl available(PhysicalModelRepository models) {
    var control = mock(RecordMaintenanceControl.class);
    org.mockito.stubbing.Answer<Object> locked = invocation -> {
      String tenant = invocation.getArgument(0);
      Collection<String> ids = invocation.getArgument(1);
      var result = new LinkedHashMap<String, PhysicalModelEntity>();
      for (String id : ids.stream().distinct().sorted().toList()) {
        result.put(id, models.findActiveByIdForUpdate(tenant, id).orElseThrow(() ->
            new DnaRuntimeException(DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND, "Fixture model was not found")));
      }
      return result;
    };
    when(control.lockModels(anyString(), any())).thenAnswer(locked);
    when(control.lockAvailable(anyString(), any())).thenAnswer(locked);
    return control;
  }
}
