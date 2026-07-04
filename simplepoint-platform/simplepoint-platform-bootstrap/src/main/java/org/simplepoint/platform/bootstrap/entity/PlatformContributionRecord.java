package org.simplepoint.platform.bootstrap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.simplepoint.platform.bootstrap.id.PlatformContributionRecordId;

/**
 * Stores the application state of a platform bootstrap contribution.
 */
@Data
@Entity
@DynamicUpdate
@DynamicInsert
@Table(name = "simpoint_platform_contribution")
@IdClass(PlatformContributionRecordId.class)
public class PlatformContributionRecord implements Serializable {

  /**
   * Contribution is currently running.
   */
  public static final String STATUS_RUNNING = "RUNNING";

  /**
   * Contribution has been applied successfully.
   */
  public static final String STATUS_APPLIED = "APPLIED";

  /**
   * Contribution failed.
   */
  public static final String STATUS_FAILED = "FAILED";

  @Id
  @Column(length = 128)
  private String serviceName;

  @Id
  @Column(length = 128)
  private String moduleCode;

  @Id
  @Column(length = 64)
  private String contributionType;

  @Id
  @Column(length = 160)
  private String contributionKey;

  @Column(length = 64)
  private String version;

  @Column(length = 256)
  private String checksum;

  @Column(length = 32)
  private String status;

  @Column(length = 1024)
  private String error;

  private LocalDateTime startedAt;

  private LocalDateTime appliedAt;

  private LocalDateTime updatedAt;
}
