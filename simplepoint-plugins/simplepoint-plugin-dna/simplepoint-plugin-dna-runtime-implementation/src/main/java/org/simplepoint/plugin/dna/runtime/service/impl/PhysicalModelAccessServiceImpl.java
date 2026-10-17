package org.simplepoint.plugin.dna.runtime.service.impl;

import java.util.List;
import java.util.function.Supplier;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelAccessService;
import org.springframework.stereotype.Service;

/** Retries first-row contention outside the rolled-back resource transaction. */
@Service
public class PhysicalModelAccessServiceImpl implements PhysicalModelAccessService {
  private final PhysicalModelResourceStateService state;

  /** Keeps resource mutation transactions inside a separate Spring boundary. */
  public PhysicalModelAccessServiceImpl(final PhysicalModelResourceStateService state) {
    this.state = state;
  }

  @Override
  public PhysicalModelResourceBindingView project(final PhysicalModelResourceProjectionCommand command) {
    return retryCreation(() -> state.project(command));
  }

  @Override
  public PhysicalModelResourceBindingView bind(final PhysicalModelResourceBindingCommand command) {
    return retryCreation(() -> state.bind(command));
  }

  @Override
  public void unbind(final PhysicalModelResourceBindingCommand command) {
    retryCreation(() -> {
      state.unbind(command);
      return null;
    });
  }

  @Override
  public List<PhysicalModelResourceBindingView> list(final String modelId) {
    return state.list(modelId);
  }

  private static <T> T retryCreation(final Supplier<T> operation) {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return operation.get();
      } catch (ResourceProjectionCreateRaceException failure) {
        if (attempt == 1) {
          throw new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED,
              "Resource projection could not be persisted", failure);
        }
      }
    }
    throw new IllegalStateException("Unreachable resource projection retry");
  }
}
