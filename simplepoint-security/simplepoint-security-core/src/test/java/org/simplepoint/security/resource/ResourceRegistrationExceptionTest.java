package org.simplepoint.security.resource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResourceRegistrationExceptionTest {
  @Test
  void decoderRetainsEveryKnownCodeAndDoesNotTrustRemoteText() {
    var decoder = new ResourceRegistrationException.Decoder();
    for (var code : ResourceRegistrationException.Code.values()) {
      var failure = new ResourceRegistrationException(code);
      var restored =
          (ResourceRegistrationException)
              decoder.decode(failure.remoteErrorCode(), "submitted-secret-or-untrusted-text");
      assertThat(restored.code()).isEqualTo(code);
      assertThat(restored.messageKey()).startsWith("resources.registration.");
      assertThat(restored.getMessage()).doesNotContain("submitted-secret-or-untrusted-text");
    }
    assertThat(decoder.decode(null, "ignored")).isNull();
    assertThat(decoder.decode("FOREIGN:INVALID_DECLARATION", "ignored")).isNull();
    assertThat(decoder.decode("RESOURCE_REGISTRATION:UNKNOWN", "ignored")).isNull();
  }
}
