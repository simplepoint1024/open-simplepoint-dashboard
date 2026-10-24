package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

final class RecordCalculationTestSupport {
  private RecordCalculationTestSupport() {}

  static RecordCalculationAccess access(RuntimeRecordModelResolver models, RuntimeRecordAccessPolicy policy, RecordSqlCompiler sql) {
    return new RecordCalculationAccess(new RecordCalculationWriteSupport(models,
        new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules()), sql), policy);
  }
}
