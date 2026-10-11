package org.simplepoint.dna.runtime;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.RecordCommandService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin authenticated transport for APP form Runtime record calls. */
@RestController
@RequestMapping("/internal/dna/runtime/records")
@PreAuthorize("isAuthenticated()")
@Tag(name = "DNA Runtime Record", description = "结构化表单记录读写")
public class RuntimeRecordController {

  private final RecordQueryService queryService;
  private final RecordCommandService commandService;
  private final RecordTransactionService transactionService;

  /** Creates the transport adapter without duplicating Runtime orchestration. */
  public RuntimeRecordController(
      final RecordQueryService queryService,
      final RecordCommandService commandService,
      final RecordTransactionService transactionService
  ) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.transactionService = transactionService;
  }

  @PostMapping("/page")
  public Response<RecordPageView> page(@RequestBody final RecordPageQuery query) {
    return Response.okay(queryService.page(query));
  }

  @PostMapping("/count")
  public Response<Long> count(@RequestBody final RecordCountQuery query) {
    return Response.okay(queryService.count(query));
  }

  @PostMapping("/get")
  public Response<RecordView> get(@RequestBody final RecordLookupQuery query) {
    return Response.okay(queryService.get(query));
  }

  @PostMapping("/create")
  public Response<RecordCommandResult> create(@RequestBody final RecordCreateCommand command) {
    return Response.okay(commandService.create(command));
  }

  @PostMapping("/update")
  public Response<RecordCommandResult> update(@RequestBody final RecordUpdateCommand command) {
    return Response.okay(commandService.update(command));
  }

  @PostMapping("/delete")
  public Response<RecordCommandResult> delete(@RequestBody final RecordDeleteCommand command) {
    return Response.okay(commandService.delete(command));
  }

  @PostMapping("/transaction")
  public Response<RecordTransactionResult> transaction(
      @RequestBody final RecordTransactionCommand command
  ) {
    return Response.okay(transactionService.execute(command));
  }
}
