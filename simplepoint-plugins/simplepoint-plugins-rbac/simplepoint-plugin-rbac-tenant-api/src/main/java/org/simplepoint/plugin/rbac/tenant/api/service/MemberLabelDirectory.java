package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteOperation;

/** Internal projection source; never a user directory or an authorization decision. */
@RemoteContract(name = "saas.member-label-directory", version = "1")
public interface MemberLabelDirectory {
  /** Returns the durable source revision, including its installation epoch. */
  @RemoteOperation(idempotent = true)
  Revision revision();

  /** Reads a bounded, revision-fenced page of active tenant member labels. */
  @RemoteOperation(idempotent = true)
  Page page(Request request);
}
