package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

/** Server-owned base fields shared by dynamic records, form projections and permission catalogs. */
public enum RecordSystemField {
  TENANT("tenantId", "tenant_id", "所属租户", LogicalFieldType.STRING),
  DEPARTMENT("createOrgDeptId", "created_org_dept_id", "创建部门", LogicalFieldType.STRING),
  CREATOR("createdBy", "created_by", "创建人", LogicalFieldType.STRING),
  CREATED_AT("createdAt", "created_at", "创建时间", LogicalFieldType.DATETIME),
  UPDATER("updatedBy", "updated_by", "更新人", LogicalFieldType.STRING),
  UPDATED_AT("updatedAt", "updated_at", "更新时间", LogicalFieldType.DATETIME),
  DELETED_AT("deletedAt", "deleted_at", "删除时间", LogicalFieldType.DATETIME);

  private final String property;
  private final String storageCode;
  private final String title;
  private final LogicalFieldType type;

  RecordSystemField(String property, String storageCode, String title, LogicalFieldType type) {
    this.property = property;
    this.storageCode = storageCode;
    this.title = title;
    this.type = type;
  }

  /** Returns the platform base-entity property name. */
  public String property() {
    return property;
  }

  /** Returns the server-controlled physical column name. */
  public String storageCode() {
    return storageCode;
  }

  /** Returns the default display label. */
  public String title() {
    return title;
  }

  /** Returns the logical storage type. */
  public LogicalFieldType type() {
    return type;
  }


  /** Returns the immutable server-owned storage codes. */
  public static List<String> storageCodes() {
    return Arrays.stream(values()).map(RecordSystemField::storageCode).toList();
  }

  /** Returns platform property names reserved against business-field collisions. */
  public static Set<String> properties() {
    return Arrays.stream(values()).map(RecordSystemField::property).collect(Collectors.toUnmodifiableSet());
  }

  /** Looks up a platform property without accepting arbitrary storage identifiers. */
  public static RecordSystemField findProperty(String name) {
    return Arrays.stream(values()).filter(field -> field.property.equals(name)).findFirst().orElse(null);
  }
}
