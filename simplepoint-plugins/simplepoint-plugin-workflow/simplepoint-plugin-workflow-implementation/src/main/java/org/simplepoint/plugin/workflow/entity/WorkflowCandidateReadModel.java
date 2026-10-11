package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

/** Exists-only candidate projection: duplicate user/group links must never multiply inbox rows. */
@Entity
@Immutable
@Getter
@Subselect(
    "select ID_ as id, TASK_ID_ as task_id, USER_ID_ as user_id, GROUP_ID_ as group_id "
        + "from ACT_RU_IDENTITYLINK where TYPE_ = 'candidate' and TASK_ID_ is not null")
public class WorkflowCandidateReadModel {
  @Id private String id;

  @Column(name = "task_id")
  private String taskId;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "group_id")
  private String groupId;
}
