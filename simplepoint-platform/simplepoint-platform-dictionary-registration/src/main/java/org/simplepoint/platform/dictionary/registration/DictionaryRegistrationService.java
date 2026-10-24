package org.simplepoint.platform.dictionary.registration;

import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.remoting.RemoteContract;

/** Only approved metadata may cross this boundary; no entity management methods are exported. */
@RemoteContract(
    name = "security.dictionary-registration",
    version = "1",
    isolateLocalCalls = true,
    errorDecoder = DictionaryRegistrationException.Decoder.class)
public interface DictionaryRegistrationService {
  /** Atomically applies the approved declaration or returns its durable previous completion. */
  DictionaryRegistrationReceipt apply(DictionaryContribution declaration);
}
