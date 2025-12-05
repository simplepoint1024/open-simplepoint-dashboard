package org.simplepoint.common.server;

import java.util.Map;
import org.simplepoint.data.datasource.context.DataSourceContextHolder;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tenant")
public class TestController {

  private final UsersService usersService;

  public TestController(UsersService usersService) {
    this.usersService = usersService;
  }

  @RequestMapping
  public String switchTenant(@RequestParam("name") String name) {
    DataSourceContextHolder.set(name);
    Page<User> limit = usersService.limit(Map.of(), Pageable.ofSize(10));
    System.out.println("User count: " + limit.getTotalElements());
    return "Switched to tenant: " + name;
  }
}
