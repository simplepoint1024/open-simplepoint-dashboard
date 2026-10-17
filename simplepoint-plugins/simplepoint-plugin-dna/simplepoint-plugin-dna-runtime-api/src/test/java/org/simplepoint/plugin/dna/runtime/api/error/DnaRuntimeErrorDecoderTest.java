package org.simplepoint.plugin.dna.runtime.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DnaRuntimeErrorDecoderTest {

  @Test
  void restoresEveryDeclaredCodeWithoutTransmittingInternalCauses() {
    var decoder = new DnaRuntimeErrorDecoder();
    for (var code : DnaRuntimeErrorCode.values()) {
      var original = new DnaRuntimeException(code, "Rejected", new IllegalStateException("internal"));
      var restored = decoder.decode(original.remoteErrorCode(), original.getMessage());
      assertThat(restored.errorCode()).isEqualTo(code);
      assertThat(restored.getMessage()).isEqualTo("Rejected");
      assertThat(restored.getCause()).isNull();
    }
  }

  @Test
  void leavesUnknownAndOtherContractCodesToTheRouter() {
    var decoder = new DnaRuntimeErrorDecoder();
    for (String code : new String[] {null, "", "RECORD_NOT_FOUND", "OTHER:RECORD_NOT_FOUND", "DNA_RUNTIME:FUTURE"}) {
      assertThat(decoder.decode(code, "Rejected")).isNull();
    }
  }
}
