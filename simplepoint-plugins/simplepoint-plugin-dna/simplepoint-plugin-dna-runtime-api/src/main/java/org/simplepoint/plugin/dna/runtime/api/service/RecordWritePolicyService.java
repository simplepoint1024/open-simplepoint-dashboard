package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;

/** Owner-management admission transitions; ordinary record callers cannot publish policy. */
public interface RecordWritePolicyService {

  /** Checks completeness under exclusive target admission and atomically advances the write version. */
  RecordWritePolicyPublicationView publish(RecordWritePolicyPublicationCommand command);

  /** Resolves the exact original intent under the target lock; committed publication is never undone. */
  RecordWritePolicyCancellationView cancel(RecordWritePolicyPublicationCommand command);
}
