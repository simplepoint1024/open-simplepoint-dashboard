package org.simplepoint.plugin.workflow.entity;

/**
 * Read-only adapter for the pinned Flowable 8 / shared platform database schema. No engine table is
 * written here. SQL is static; scope, filters and paging are always bound by Criteria. Keep this
 * adapter covered by real engine schema tests when upgrading Flowable.
 */
final class WorkflowReadSql {
  private WorkflowReadSql() {}

  // A scalar, unique tenant/user directory. Display the real name, not nickname/email/ID.
  // Removed or disabled identities are deliberately unavailable; do not invent historical names.
  static final String MEMBERS =
      """
      (select t.id as tenant_id, u.id as user_id, nullif(trim(u.name), '') as name
       from simpoint_saas_tenants t join simpoint_ac_users u
         on (u.id = t.owner_id or exists (
           select 1 from simpoint_saas_tenant_user_rel m
           where m.tenant_id = t.id and m.user_id = u.id
             and m.deleted_at is null and coalesce(m.enabled, true) = true))
       where t.deleted_at is null and u.deleted_at is null and coalesce(u.enabled, true) = true)
      """;

  private static final String TASKS =
      """
      select t.ID_ as id, t.ID_ as task_id, t.PROC_INST_ID_ as process_instance_id,
        t.TENANT_ID_ as tenant_id, 'TASK' as row_type, t.NAME_ as task_name,
        t.ASSIGNEE_ as assignee_id, coalesce(d.actor_id, z.actor_id, t.COMPLETED_BY_) as actor_id,
        t.START_TIME_ as created_at, t.DUE_DATE_ as due_at, t.END_TIME_ as handled_at,
        t.PRIORITY_ as priority, coalesce(d.comment, z.comment) as comment,
        case when d.action_type is not null then d.action_type
          when t.END_TIME_ is null then 'PENDING'
          when t.DELETE_REASON_ is not null then
            case when z.action_type is not null then z.action_type else 'CANCELED' end
          else 'COMPLETED' end as outcome,
        case when r.ID_ is not null and r.SUSPENSION_STATE_ = 1 then true else false end as active,
        coalesce(d.actor_id, t.COMPLETED_BY_) as decision_actor_id
      from ACT_HI_TASKINST t
      left join ACT_RU_TASK r on r.ID_ = t.ID_ and r.TENANT_ID_ = t.TENANT_ID_
      left join simpoint_workflow_actions d
        on d.decision_task_id = t.ID_ and d.tenant_id = t.TENANT_ID_
      left join simpoint_workflow_actions z
        on z.terminal_instance_id = t.PROC_INST_ID_ and z.tenant_id = t.TENANT_ID_
        and t.DELETE_REASON_ = concat('simplepoint:', z.action_type)
      """;

  private static final String PROCESS_ACTIONS =
      """
      select concat('action:', a.id) as id, cast(null as varchar(64)) as task_id,
        a.process_instance_id, a.tenant_id, 'PROCESS_ACTION' as row_type,
        cast(null as varchar(255)) as task_name, cast(null as varchar(64)) as assignee_id,
        a.actor_id, a.occurred_at as created_at, cast(null as timestamp) as due_at,
        a.occurred_at as handled_at, cast(null as integer) as priority,
        a.comment, a.action_type as outcome, false as active, a.actor_id as decision_actor_id
      from simpoint_workflow_actions a
      where a.action_type in ('WITHDRAWN', 'CANCELED') and not exists (
        select 1 from ACT_HI_TASKINST t where t.PROC_INST_ID_ = a.process_instance_id
          and t.TENANT_ID_ = a.tenant_id and t.ASSIGNEE_ = a.actor_id
          and t.DELETE_REASON_ = concat('simplepoint:', a.action_type) and t.END_TIME_ is not null)
      """;

  static final String INBOX =
      """
      select w.*, i.title, i.definition_name, i.starter_id,
        case when h.END_TIME_ is not null and i.status = 'RUNNING' then
          case when h.DELETE_REASON_ is null then 'COMPLETED' else 'CANCELED' end
          else i.status end as instance_status,
        starter.name as starter_name, assignee.name as assignee_name, actor.name as actor_name
      from (
      """
          + TASKS
          + " union all "
          + PROCESS_ACTIONS
          + """
            ) w join simpoint_workflow_instances i
              on i.process_instance_id = w.process_instance_id and i.tenant_id = w.tenant_id
            join ACT_HI_PROCINST h on h.ID_ = i.process_instance_id and h.TENANT_ID_ = i.tenant_id
            left join
            """
          + MEMBERS
          + " starter on starter.tenant_id = i.tenant_id and starter.user_id = i.starter_id "
          + " left join "
          + MEMBERS
          + " assignee on assignee.tenant_id = i.tenant_id and assignee.user_id = w.assignee_id "
          + " left join "
          + MEMBERS
          + " actor on actor.tenant_id = i.tenant_id and actor.user_id = w.actor_id ";

  static final String MATTERS =
      """
select i.process_instance_id as id, i.tenant_id, i.definition_id, i.title, i.definition_name,
  i.definition_version, i.business_key, i.starter_id, starter.name as starter_name,
  case when h.END_TIME_ is not null and i.status = 'RUNNING' then
    case when h.DELETE_REASON_ is null then 'COMPLETED' else 'CANCELED' end
    else i.status end as status,
  i.started_at, coalesce(i.ended_at, h.END_TIME_) as ended_at,
  i.previous_instance_id, successor.process_instance_id as next_instance_id,
  case when p.ID_ is not null and p.SUSPENSION_STATE_ = 1 and h.END_TIME_ is not null
    then true else false end as resubmission_version_available
from simpoint_workflow_instances i
left join simpoint_workflow_instances successor
  on successor.previous_instance_id = i.process_instance_id and successor.tenant_id = i.tenant_id
left join ACT_HI_PROCINST h on h.ID_ = i.process_instance_id and h.TENANT_ID_ = i.tenant_id
left join simpoint_workflow_definition_versions v on v.id = i.definition_version_id and v.tenant_id = i.tenant_id
left join ACT_RE_PROCDEF p on p.ID_ = v.flowable_process_definition_id and p.TENANT_ID_ = i.tenant_id
left join
      """
          + MEMBERS
          + " starter on starter.tenant_id = i.tenant_id and starter.user_id = i.starter_id ";
}
