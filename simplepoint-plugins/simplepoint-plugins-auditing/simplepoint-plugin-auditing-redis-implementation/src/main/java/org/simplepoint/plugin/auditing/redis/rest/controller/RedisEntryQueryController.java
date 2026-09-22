package org.simplepoint.plugin.auditing.redis.rest.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.schema.AnnotatedTableSchemaProvider;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryMetadata;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryFieldPolicy;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryQueryService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Query and schema retain the existing Redis instance-wide read permission. */
@RestController
@RequestMapping("/redis/entries/query")
@PreAuthorize("hasRole('Administrator') or hasAuthority('redis.entries.view')")
public class RedisEntryQueryController {
  private final RedisEntryQueryService queries;
  private final AnnotatedTableSchemaProvider schemas;

  /** Uses the same platform schema provider as SQL-backed standard tables. */
  public RedisEntryQueryController(RedisEntryQueryService queries, DetailsProviderService details) {
    this.queries = queries;
    this.schemas = new AnnotatedTableSchemaProvider(details);
  }

  /** The page is null until READY; callers pin the returned snapshotId while paging. */
  @GetMapping
  public Response<?> query(
      @RequestParam Map<String, String> parameters,
      Pageable pageable,
      @RequestParam(value = "scanPattern", required = false) String scanPattern,
      @RequestParam(value = "snapshotId", required = false) String snapshotId,
      @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
    Map<String, String> filters = new LinkedHashMap<>(parameters);
    filters.keySet().removeAll(java.util.Set.of("scanPattern", "snapshotId", "refresh"));
    return Response.of(
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(queries.query(filters, pageable, scanPattern, snapshotId, refresh)));
  }

  /** Hidden key identities deny schema and totals as well as row contents. */
  @GetMapping("/schema")
  public Response<?> schema() {
    RedisEntryQueryService.requireIdentity();
    var context = AuthorizationContextHolder.getContext();
    var result = new LinkedHashMap<>(schemas.schema(RedisEntryMetadata.class, context));
    boolean editable = RedisEntryFieldPolicy.canEdit();
    result.put(
        "buttons",
        schemas.buttonSchemas(RedisEntryMetadata.class, context).stream()
            .filter(button -> !"edit".equals(button.get("key")) || editable)
            .filter(
                button ->
                    !"add".equals(button.get("key"))
                        || editable && RedisEntryFieldPolicy.writable("key"))
            .toList());
    return Response.of(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result));
  }

  /** Only controlled validation text is exposed, never Redis connection or script errors. */
  @ExceptionHandler(InvalidQueryException.class)
  public Response<?> invalid(InvalidQueryException error) {
    return Response.of(
        ResponseEntity.badRequest()
            .cacheControl(CacheControl.noStore())
            .body(Map.of("code", "INVALID_QUERY", "message", error.getMessage())));
  }
}
