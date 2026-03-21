package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
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
 * Dictionary item controller.
 */
@RestController
@RequestMapping({"/dictionary-items", "/platform/dictionary-items"})
@Tag(name = "字典项管理", description = "用于管理系统中的动态字典项")
public class DictionaryItemController extends BaseController<DictionaryItemService, DictionaryItem, String> {

  public DictionaryItemController(DictionaryItemService service) {
    super(service);
  }

  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.view') or hasAuthority('dictionaries.config.item')")
  @Operation(summary = "分页查询字典项", description = "根据提供的属性和分页参数，检索字典项的分页列表")
  public Response<Page<DictionaryItem>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), DictionaryItem.class);
  }

  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.create')")
  @Operation(summary = "添加字典项", description = "添加一个新的字典项到系统中")
  public Response<DictionaryItem> add(@RequestBody DictionaryItem data) {
    return ok(service.create(data));
  }

  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.edit')")
  @Operation(summary = "修改字典项", description = "修改一个已存在的字典项信息")
  public Response<DictionaryItem> modify(@RequestBody DictionaryItem data) {
    return ok(service.modifyById(data));
  }

  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dictionaries.delete')")
  @Operation(summary = "删除字典项", description = "根据提供的字典项ID集合，删除一个或多个字典项")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
