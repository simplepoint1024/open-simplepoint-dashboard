package org.simplepoint.plugin.rbac.core.api.service;

import org.simplepoint.plugin.rbac.core.api.pojo.command.PlatformAccountCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PlatformAccountView;
import org.simplepoint.security.entity.PlatformSecurityAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PlatformAccountService {
  java.util.Map<String, Object> capabilities();
  java.util.Map<String, Object> accountSchema();
  java.util.Map<String, Object> auditSchema();
  Page<PlatformAccountView> list(java.util.Map<String, String> attributes, Pageable pageable);
  PlatformAccountView create(PlatformAccountCommand command);
  PlatformAccountView update(String userId, PlatformAccountCommand command);
  PlatformAccountView assignIdentity(String userId, PlatformAccountCommand command);
  Page<PlatformSecurityAudit> audit(java.util.Map<String, String> attributes, Pageable pageable);
}
