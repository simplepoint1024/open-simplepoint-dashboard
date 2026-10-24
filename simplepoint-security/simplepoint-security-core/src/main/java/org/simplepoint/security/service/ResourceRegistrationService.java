package org.simplepoint.security.service;

import java.util.Set;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.resource.ResourceRegistrationException;

/** Public declaration registration; resource entity management stays inside identity. */
@RemoteContract(
    name = "security.resource",
    version = "4",
    isolateLocalCalls = true,
    errorDecoder = ResourceRegistrationException.Decoder.class)
public interface ResourceRegistrationService {

  /**
   * Version 4 adds explicit shared-directory mounts to durable deployment authority checks; older
   * providers must not receive this operation through fallback.
   *
   * <p>Synchronizes a complete nonempty resource snapshot for its logical owner. Codes and paths
   * cannot claim another owner's records, including legacy records without an adopted owner. An
   * empty snapshot retains the existing no-op behavior; it is not an uninstall command.
   */
  void sync(String owner, Set<ResourceDeclaration> declarations);
}
