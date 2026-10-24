package org.simplepoint.plugin.rbac.tenant.service.initialize;

import jakarta.persistence.PersistenceException;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException.Code;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationReceipt;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationService;
import org.simplepoint.plugin.rbac.tenant.api.error.DictionaryWriteException;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Authenticates before entering persistence; local calls require the same isolated service scope.
 */
@Service
@RemoteProvider
public class DictionaryRegistrationProvider implements DictionaryRegistrationService {
  private final DictionaryRegistrationApplication application;

  /** Receives only the transactional application port, never the installer administration port. */
  public DictionaryRegistrationProvider(DictionaryRegistrationApplication application) {
    this.application = application;
  }

  @Override
  public DictionaryRegistrationReceipt apply(DictionaryContribution declaration) {
    String client =
        ServiceClientIdentity.authenticatedClientId(
                SecurityContextHolder.getContext().getAuthentication())
            .orElseThrow(() -> new DictionaryRegistrationException(Code.ACCESS_DENIED));
    if (declaration == null) {
      throw new DictionaryRegistrationException(Code.INVALID_DECLARATION);
    }
    try {
      return application.apply(declaration, client);
    } catch (DictionaryWriteException failure) {
      var rejection =
          new DictionaryRegistrationException(
              failure.code() == DictionaryWriteException.Code.CONTEXT_CONFLICT
                  ? Code.CATALOG_CONTEXT_CONFLICT
                  : Code.CATALOG_UNAVAILABLE);
      rejection.initCause(failure);
      throw rejection;
    } catch (PersistenceException | DataAccessException failure) {
      var rejection = new DictionaryRegistrationException(Code.CATALOG_UNAVAILABLE);
      rejection.initCause(failure);
      throw rejection;
    }
  }
}
