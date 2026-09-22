package org.simplepoint.plugin.auditing.redis.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.annotation.PermissionResource;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;

/** Immutable metadata observed during a scan; never contains Redis values. */
@PermissionResource(RedisEntrySummary.class)
@ButtonDeclarations({
  @ButtonDeclaration(
      key = "add",
      title = "i18n:table.button.add",
      authority = "redis.entries.create",
      icon = "PlusOutlined",
      sort = 0,
      argumentMaxSize = 0),
  @ButtonDeclaration(
      key = "view",
      title = "i18n:monitoring.redis.view",
      authority = "redis.entries.view",
      icon = "EyeOutlined",
      sort = 1,
      argumentMinSize = 1,
      argumentMaxSize = 1),
  @ButtonDeclaration(
      key = "edit",
      title = "i18n:table.button.edit",
      authority = "redis.entries.edit",
      icon = "EditOutlined",
      sort = 2,
      argumentMinSize = 1,
      argumentMaxSize = 1),
  @ButtonDeclaration(
      key = "delete",
      title = "i18n:table.button.delete",
      authority = "redis.entries.delete",
      icon = "DeleteOutlined",
      sort = 3,
      argumentMinSize = 1,
      argumentMaxSize = 1,
      danger = true)
})
@TableQuery(
    value = {
      @QueryField("key"), @QueryField("type"), @QueryField("ttlSeconds"),
      @QueryField("persistent"), @QueryField("size"), @QueryField("editable"),
      @QueryField("observedAt")
    },
    maxPageSize = 100)
public record RedisEntryMetadata(
    @Schema(title = "i18n:monitoring.redis.key") String key,
    @Schema(title = "i18n:monitoring.redis.type") RedisEntryType type,
    @Schema(title = "i18n:monitoring.redis.ttlObserved") Long ttlSeconds,
    @Schema(title = "i18n:monitoring.redis.permanentFlag") Boolean persistent,
    @Schema(title = "i18n:monitoring.redis.sizeObserved") Long size,
    @Schema(title = "i18n:monitoring.redis.stringKey") Boolean editable,
    @Schema(title = "i18n:monitoring.redis.observedAt") Instant observedAt) {}
