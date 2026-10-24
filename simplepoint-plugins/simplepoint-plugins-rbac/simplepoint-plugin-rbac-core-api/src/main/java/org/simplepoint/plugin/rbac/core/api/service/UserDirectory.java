package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Optional;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserDirectoryEntry;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserDirectoryPage;
import org.simplepoint.remoting.RemoteContract;

/**
 * Account directory for authenticated platform services, separate from account management and
 * login.
 */
@RemoteContract(name = "security.user-directory")
public interface UserDirectory {

  /**
   * Finds a nondeleted account snapshot; the consumer must check enabled before assigning access.
   */
  Optional<UserDirectoryEntry> findById(String userId);

  /** Searches active accounts by a literal email/phone prefix, with at most 50 rows per page. */
  UserDirectoryPage search(String keyword, int page, int size);
}
