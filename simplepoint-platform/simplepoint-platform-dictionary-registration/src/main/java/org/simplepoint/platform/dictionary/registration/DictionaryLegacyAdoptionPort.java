package org.simplepoint.platform.dictionary.registration;

import java.util.Optional;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Command;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Preview;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Receipt;

/** Trusted installation port; its owner retains all data, locking and transaction semantics. */
public interface DictionaryLegacyAdoptionPort {
  /** Reads complete bounded data for review without modifying business rows. */
  Preview preview(DictionaryContribution declaration);

  /** Atomically adopts authority and records completion, retaining business values. */
  Receipt adopt(Command command);

  /** Reads actual committed completion; it must never execute adoption as a lookup fallback. */
  Optional<Receipt> findCompletion(String module);
}
