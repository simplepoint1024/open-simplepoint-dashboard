package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dictionary controller.
 */
@RestController
@RequestMapping({"/dictionaries", "/platform/dictionaries"})
@Tag(name = "字典管理", description = "用于管理系统中的动态字典")
public class DictionaryController extends BaseController<DictionaryService, Dictionary, String> {

  public DictionaryController(DictionaryService service) {
    super(service);
  }

  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.view')")
  @Operation(summary = "分页查询字典", description = "根据提供的属性和分页参数，检索字典的分页列表")
  public Response<Page<Dictionary>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Dictionary.class);
  }

  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.create')")
  @Operation(summary = "添加字典", description = "添加一个新的字典到系统中")
  public Response<Dictionary> add(@RequestBody Dictionary data) {
    return ok(service.create(data));
  }

  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.edit')")
  @Operation(summary = "修改字典", description = "修改一个已存在的字典信息")
  public Response<Dictionary> modify(@RequestBody Dictionary data) {
    return ok(service.modifyById(data));
  }

  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.delete')")
  @Operation(summary = "删除字典", description = "根据提供的字典ID集合，删除一个或多个字典")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  @GetMapping("/options")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "获取字典选项", description = "根据字典编码返回启用状态的字典选项")
  public Response<Collection<DictionaryOptionVo>> options(@RequestParam("dictionaryCode") String dictionaryCode) {
    return ok(service.options(dictionaryCode));
  }
}
