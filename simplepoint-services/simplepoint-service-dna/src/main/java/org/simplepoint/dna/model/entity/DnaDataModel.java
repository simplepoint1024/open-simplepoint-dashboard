package org.simplepoint.dna.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** A saved query model definition. Physical application tables are a separate concern. */
@Entity
@Table(name = "simpoint_dna_data_models", indexes = {
    @Index(name = "idx_dna_model_updated", columnList = "deleted_at,updated_at"),
    @Index(name = "idx_dna_model_type", columnList = "model_type,deleted_at")
})
@org.simplepoint.core.query.TableQuery(maxPageSize = 100, value = {
    @org.simplepoint.core.query.QueryField("name"),
    @org.simplepoint.core.query.QueryField("modelType"),
    @org.simplepoint.core.query.QueryField("description"),
    @org.simplepoint.core.query.QueryField("status"),
    @org.simplepoint.core.query.QueryField("updatedAt")
})
@Getter
@Setter
public class DnaDataModel {
  @Id
  @Column(length = 36)
  private String id;

  @Column(length = 120, nullable = false)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(name = "model_type", length = 24, nullable = false)
  private String modelType;

  @Column(name = "data_source_id", length = 64)
  private String dataSourceId;

  @Column(name = "sql_text", columnDefinition = "text")
  private String sqlText;

  @Column(length = 20, nullable = false)
  private String status;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
