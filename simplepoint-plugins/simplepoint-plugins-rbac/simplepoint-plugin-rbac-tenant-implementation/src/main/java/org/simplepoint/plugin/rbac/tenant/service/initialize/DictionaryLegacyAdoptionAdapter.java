package org.simplepoint.plugin.rbac.tenant.service.initialize;

import java.util.Optional;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Command;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Preview;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption.Receipt;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoptionPort;
import org.springframework.stereotype.Component;

/** Domain-owned binding; the installer sees a pure port, while the owner retains transactions. */
@Component
public final class DictionaryLegacyAdoptionAdapter implements DictionaryLegacyAdoptionPort {
  private final DictionaryLegacyAdoptions owner;

  /** Calls the injected owner proxy rather than bypassing it with self-invocation. */
  public DictionaryLegacyAdoptionAdapter(DictionaryLegacyAdoptions owner) {
    this.owner = owner;
  }

  @Override
  public Preview preview(DictionaryContribution declaration) {
    return owner.preview(declaration);
  }

  @Override
  public Receipt adopt(Command command) {
    return owner.adopt(command);
  }

  @Override
  public Optional<Receipt> findCompletion(String module) {
    return owner.findCompletion(module);
  }
}
