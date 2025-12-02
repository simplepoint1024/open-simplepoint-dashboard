package org.simplepoint.data.initialize.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.simplepoint.data.initialize.entity.id.DataInitializeId;

/**
 * DataInitialize entity class.
 */
@Data
@Entity
@DynamicUpdate
@DynamicInsert
@IdClass(DataInitializeId.class)
public class DataInitialize implements Serializable {
  public static final Integer STATUS_INIT = 0;
  public static final Integer STATUS_DONE = 1;
  public static final Integer STATUS_FAIL = 2;

  @Id
  private String serviceName;

  @Id
  private String moduleName;

  private Integer initStatus;

  private String error;

  private LocalDateTime createTime;

  private LocalDateTime doneTime;
}
