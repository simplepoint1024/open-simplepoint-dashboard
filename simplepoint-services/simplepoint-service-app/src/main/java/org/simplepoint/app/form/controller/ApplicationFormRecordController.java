package org.simplepoint.app.form.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordSchemaService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService;
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

/** Browser-facing page-bound record transport; physical identities remain server-side. */
@RestController
@RequestMapping("/runtime/applications/{applicationId}/pages/{pageId}/records")
@PreAuthorize("isAuthenticated()")
@Tag(name = "应用表单数据", description = "已发布应用页面的结构化记录读写")
public class ApplicationFormRecordController {

  private final ApplicationFormRecordService records;
  private final ApplicationFormRecordSchemaService schemas;

  /** Creates the thin runtime record transport. */
  public ApplicationFormRecordController(
      final ApplicationFormRecordService records,
      final ApplicationFormRecordSchemaService schemas
  ) {
    this.records = records;
    this.schemas = schemas;
  }

  /** Returns the platform SimpleTable schema filtered by dynamic field and action policy. */
  @GetMapping("/schema")
  @Operation(summary = "读取表单记录表格协议")
  public Response<Map<String, Object>> schema(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId
  ) {
    return Response.okay(schemas.schema(applicationId, pageId));
  }

  /** Reads an authorized server page. */
  @PostMapping("/query")
  @Operation(summary = "分页查询表单记录")
  public Response<ApplicationFormRecordPageView> page(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId,
      @RequestBody final ApplicationFormRecordPageQuery query
  ) {
    return Response.okay(records.page(applicationId, pageId, query));
  }

  /** Reads one authorized active record. */
  @GetMapping("/{recordId}")
  @Operation(summary = "读取表单记录")
  public Response<ApplicationFormRecordView> get(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId,
      @PathVariable("recordId") final String recordId
  ) {
    return Response.okay(records.get(applicationId, pageId, recordId));
  }

  /** Creates one draft or submitted record idempotently. */
  @PostMapping
  @Operation(summary = "新增表单记录")
  public Response<ApplicationFormRecordMutationView> create(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId,
      @RequestBody final CreateApplicationFormRecordCommand command
  ) {
    return Response.okay(records.create(applicationId, pageId, command));
  }

  /** Updates one record and its lifecycle state using optimistic locking. */
  @PutMapping("/{recordId}")
  @Operation(summary = "修改表单记录")
  public Response<ApplicationFormRecordMutationView> update(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId,
      @PathVariable("recordId") final String recordId,
      @RequestBody final UpdateApplicationFormRecordCommand command
  ) {
    return Response.okay(records.update(applicationId, pageId, recordId, command));
  }

  /** Soft deletes one record using optimistic locking. */
  @DeleteMapping("/{recordId}")
  @Operation(summary = "删除表单记录")
  public Response<Void> delete(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId,
      @PathVariable("recordId") final String recordId,
      @RequestParam("expectedVersion") final long expectedVersion,
      @RequestParam("formId") final String formId,
      @RequestParam("formVersion") final int formVersion,
      @RequestParam("idempotencyKey") final String idempotencyKey
  ) {
    records.delete(applicationId, pageId, recordId, expectedVersion,
        new ApplicationFormRuntimeIdentity(formId, formVersion), idempotencyKey);
    return Response.okay();
  }
}
