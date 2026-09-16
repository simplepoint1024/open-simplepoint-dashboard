package org.simplepoint.plugin.rbac.core.api.service;

import org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TenantMemberService {
  java.util.Map<String, Boolean> capabilities();
  Page<TenantMemberView> list(Pageable pageable);
  void add(TenantMemberCommand command);
  void update(String userId, TenantMemberCommand command);
  void remove(String userId);
}
