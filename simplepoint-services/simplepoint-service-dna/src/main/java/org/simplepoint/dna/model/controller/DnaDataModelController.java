package org.simplepoint.dna.model.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.dna.model.service.DnaDataModelCatalog;
import org.simplepoint.dna.model.service.DnaDataModelCatalog.Command;
import org.simplepoint.dna.model.service.DnaDataModelCatalog.Detail;
import org.simplepoint.dna.model.service.DnaDataModelCatalog.Permissions;
import org.simplepoint.dna.model.service.DnaDataModelCatalog.Summary;
import org.simplepoint.dna.model.service.DnaDataModelCatalog.SourceOption;
import org.simplepoint.dna.model.service.DnaDataModelCatalog.TableSchema;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform/dna/models/data-models")
@PreAuthorize("hasRole('Administrator') or hasAuthority('dna.models.data-models.view')")
@Tag(name = "DNA 数据模型", description = "平台查询模型草稿目录")
public class DnaDataModelController {
  private final DnaDataModelCatalog catalog;

  public DnaDataModelController(DnaDataModelCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/permissions")
  public Response<Permissions> permissions() {
    return Response.okay(catalog.permissions());
  }

  @GetMapping("/schema")
  public Response<TableSchema> schema() {
    return Response.okay(catalog.schema());
  }

  @GetMapping("/data-sources")
  @Operation(summary = "查询可用于原生 SQL 模型的数据源选项")
  public Response<Page<SourceOption>> dataSources(@RequestParam(name = "name", required = false) String name,
      @RequestParam(name = "id", required = false) String id,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return Response.okay(catalog.dataSources(name, id, page, size));
  }

  @GetMapping
  @Operation(summary = "分页查询数据模型")
  public Response<Page<Summary>> list(@RequestParam Map<String, String> filters,
      @PageableDefault(size = 20) Pageable pageable) {
    // Keep existing keyword callers working while the standard table uses typed filters.
    if (filters.containsKey("keyword")) {
      return Response.okay(catalog.list(filters.get("keyword"), filters.get("modelType"),
          pageable.getPageNumber(), pageable.getPageSize()));
    }
    return Response.okay(catalog.query(filters, pageable));
  }

  @GetMapping("/{id}")
  public Response<Detail> detail(@PathVariable("id") String id) {
    return Response.okay(catalog.detail(id));
  }

  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.models.data-models.create')")
  public Response<Detail> create(@RequestBody Command command) {
    return Response.okay(catalog.create(command));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.models.data-models.edit')")
  public Response<Detail> update(@PathVariable("id") String id, @RequestBody Command command) {
    return Response.okay(catalog.update(id, command));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.models.data-models.delete')")
  public Response<Void> delete(@PathVariable("id") String id,
      @RequestParam(name = "version") long version) {
    catalog.delete(id, version);
    return Response.okay();
  }
}
