// Reproducible migration contract against a disposable local PostgreSQL instance, never production.
// docker run --rm --name simplepoint-workflow-consolidation-pg -p 127.0.0.1:45439:5432
//   -e POSTGRES_USER=workflow_test -e POSTGRES_PASSWORD=workflow_test -e POSTGRES_DB=workflow_test postgres:17-alpine
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID, createHash} from 'node:crypto';

const schema = 'workflow_test_' + randomUUID().replaceAll('-', '');
const env = {...Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('PG'))),
  PGHOST: '127.0.0.1', PGPORT: '45439', PGDATABASE: 'workflow_test',
  PGUSER: 'workflow_test', PGPASSWORD: 'workflow_test', PGCONNECT_TIMEOUT: '5',
  PGOPTIONS: `-c search_path=${schema} -c statement_timeout=30000 -c lock_timeout=5000`};
const sql = text => execFileSync('psql', ['-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], {env, input: text, encoding: 'utf8'}).trim();
const root = new URL('../../', import.meta.url);
const migration = readFileSync(new URL('doc/deployment/sql/20261011-workflow-personal-messages.sql', root), 'utf8');
const structure = readFileSync(new URL('simplepoint-services/simplepoint-service-workflow/src/main/resources/schema.sql', root), 'utf8');
const navigation = readFileSync(new URL('doc/deployment/sql/20261011-workflow-personal-navigation.sql', root), 'utf8');
try {
  sql(`CREATE SCHEMA ${schema}`);
  sql(structure);
  sql(structure); // Additive schema, constraints and indexes are repeatable.
  assert.equal(sql(`SELECT count(*) FROM information_schema.columns WHERE table_schema='${schema}'
    AND table_name='simpoint_workflow_instances' AND column_name='previous_instance_id'`), '1');
  sql(`
    CREATE TABLE simpoint_system_notifications (id varchar(255) primary key, version bigint, title varchar(200),
      content varchar(8000), priority varchar(16), source varchar(64), external_key varchar(200), audience_type varchar(16),
      audience_id varchar(64), status varchar(16), deleted_at timestamptz, expire_at timestamptz, publish_at timestamptz,
      published_at timestamptz, created_at timestamptz, updated_at timestamptz, updated_by varchar(255));
    CREATE TABLE simpoint_notification_receipts (notification_id varchar(255), user_id varchar(64), read_at timestamptz,
      deleted_at timestamptz, UNIQUE(notification_id,user_id));
    CREATE TABLE simpoint_personal_messages (id varchar(255) primary key, owner_id varchar(64), workspace varchar(160),
      version bigint, title varchar(200), content varchar(8000), priority int, source varchar(64), source_key varchar(160),
      link_path varchar(512), read_at timestamptz, archived boolean, created_at timestamptz, updated_at timestamptz,
      created_by varchar(255), updated_by varchar(255), deleted_at timestamptz, UNIQUE(owner_id,workspace,source,source_key));
    CREATE TABLE ACT_HI_TASKINST (ID_ varchar(64), PROC_INST_ID_ varchar(64), TENANT_ID_ varchar(64));
    INSERT INTO simpoint_workflow_definitions (id,tenant_id,definition_key,name,status,draft_xml,created_by,created_at,updated_at)
      VALUES ('definition','tenant','approval','审批','PUBLISHED','xml','user',now(),now());
    INSERT INTO simpoint_workflow_definition_versions (id,definition_id,tenant_id,version_no,bpmn_xml,checksum,
      flowable_deployment_id,flowable_process_definition_id,published_by,published_at)
      VALUES ('version','definition','tenant',1,'xml','checksum','deploy','engine-def','user',now());
    INSERT INTO simpoint_workflow_instances (process_instance_id,tenant_id,definition_id,definition_version_id,
      definition_key,definition_name,definition_version,title,starter_id,status,started_at)
      VALUES ('instance1','tenant','definition','version','approval','审批',1,'申请','user','COMPLETED',now());
    INSERT INTO ACT_HI_TASKINST VALUES ('task1','instance1','tenant');
    INSERT INTO simpoint_workflow_notification_outbox (id,tenant_id,event_key,target_user_id,title,content,link_url,status,
      attempts,next_attempt_at,created_at)
      VALUES ('out1','tenant','task:task1:created:user','user','待办','内容','/workflow/tasks','SENT',0,now(),now()),
      ('out2','tenant','instance:instance1:completed:user','user','完成','内容','/workflow/instances','SENT',0,now(),now()),
      ('out3','tenant','instance:instance1:copied:watcher','watcher','抄送','内容','/workflow/instances','SENT',0,now(),now()),
      ('foreign','other','instance:instance1:completed:foreign','foreign','其他','内容','/workflow/instances','SENT',0,now(),now());
    INSERT INTO simpoint_system_notifications (id,version,title,content,priority,source,external_key,audience_type,audience_id,
      status,published_at,created_at)
      SELECT id,5,title,content,'NORMAL','workflow',event_key,'USER',target_user_id,'PUBLISHED','2026-01-01Z','2026-01-01Z'
      FROM simpoint_workflow_notification_outbox;
    INSERT INTO simpoint_system_notifications (id,version,title,content,source,audience_type,audience_id,status,created_at)
      VALUES ('ordinary',2,'公告','内容','system','ALL',null,'PUBLISHED',now()),
      ('unmatched',3,'未知来源','内容','workflow','USER','user','PUBLISHED',now());
    INSERT INTO simpoint_notification_receipts VALUES ('out1','user','2026-01-02Z',null),('out1','different','2026-02-01Z',null);
  `);
  sql('BEGIN;\n' + migration + '\nCOMMIT;');
  assert.equal(sql('SELECT count(*) FROM simpoint_personal_messages'), '3');
  assert.equal(sql('SELECT count(*) FROM simpoint_workflow_message_migrations'), '3');
  assert.equal(sql("SELECT count(*) FROM simpoint_system_notifications WHERE status='PUBLISHED'"), '3');
  assert.equal(sql("SELECT count(*) FROM simpoint_personal_messages WHERE workspace<>'TENANT:tenant'"), '0');
  assert.equal(sql("SELECT count(*) FROM simpoint_personal_messages WHERE read_at IS NOT NULL"), '1');
  assert.equal(sql("SELECT link_path FROM simpoint_personal_messages WHERE title='待办'"), '/my/todos?task=task1&instance=instance1');
  assert.equal(sql("SELECT link_path FROM simpoint_personal_messages WHERE title='抄送'"), '/my/items?instance=instance1&view=copied');
  assert.equal(sql("SELECT source_key FROM simpoint_personal_messages WHERE title='待办'"),
    'notification:' + createHash('sha256').update('task:task1:created:user').digest('hex'));
  const before = sql('SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM simpoint_personal_messages m');
  sql('BEGIN;\n' + migration + '\nCOMMIT;');
  assert.equal(sql('SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM simpoint_personal_messages m'), before);
  assert.equal(sql('SELECT count(*) FROM simpoint_workflow_message_migrations'), '3');
  // Deliberate failure after all writes must roll back the whole move, including status and ledger.
  sql("UPDATE simpoint_system_notifications SET status='PUBLISHED' WHERE id='out2'; DELETE FROM simpoint_workflow_message_migrations WHERE notification_id='out2'");
  assert.throws(() => sql('BEGIN;\n' + migration + '\nSELECT 1/0; COMMIT;'));
  assert.equal(sql("SELECT status FROM simpoint_system_notifications WHERE id='out2'"), 'PUBLISHED');
  assert.equal(sql("SELECT count(*) FROM simpoint_workflow_message_migrations WHERE notification_id='out2'"), '0');
  // Existing personal read/archive state and content must survive a subsequent idempotent move.
  sql("UPDATE simpoint_personal_messages SET archived=true, read_at='2026-03-01Z',content='retained' WHERE title='完成'");
  sql('BEGIN;\n' + migration + '\nCOMMIT;');
  assert.equal(sql("SELECT archived AND read_at='2026-03-01Z'::timestamptz AND content='retained' FROM simpoint_personal_messages WHERE title='完成'"), 't');
  assert.equal(sql("SELECT personal_preexisting FROM simpoint_workflow_message_migrations WHERE notification_id='out2'"), 't');
  sql(`
    CREATE TABLE simpoint_ac_resources (id varchar(36) primary key, code varchar(120), type varchar(32), name varchar(100),
      component varchar(160), route_kind varchar(32), path varchar(200), parent_id varchar(36), disabled boolean, deleted_at timestamptz);
    CREATE TABLE simpoint_saas_tenants (id varchar(36) primary key, authorization_version bigint);
    CREATE TABLE simpoint_saas_tenant_resources (tenant_id varchar(36), id varchar(36), platform_resource_id varchar(36),
      target_resource_id varchar(36), name varchar(100), type varchar(32), component varchar(160), route_kind varchar(32),
      path varchar(200), parent_id varchar(36), local_modified boolean, enabled boolean, grantable boolean,
      manual_disabled boolean, updated_at timestamptz, PRIMARY KEY(tenant_id,id));
    CREATE TABLE simpoint_ac_role_resource_grants (role_id varchar(36), resource_code varchar(120));
    INSERT INTO simpoint_ac_resources VALUES
      ('tasks','workflow.tasks.view','PAGE','兼容待办','workflow/Tasks','hidden','/workflow/tasks','root',false,null),
      ('started','workflow.instances.started.view','PAGE','兼容事项','workflow/Instances','hidden','/workflow/instances','root',false,null),
      ('admin','workflow.instances.view','PAGE','运行管理','workflow/Management','item','/workflow/management','root',false,null);
    INSERT INTO simpoint_saas_tenants VALUES ('tenant',7),('unrelated',9);
    INSERT INTO simpoint_saas_tenant_resources VALUES
      ('tenant','tasks','tasks',null,'任务中心','PAGE','workflow/Tasks','item','/workflow/tasks','root',false,true,true,false,now()),
      ('tenant','started','started',null,'自定义事项名称','PAGE','workflow/Instances','item','/my-custom-items','root',true,true,true,false,now()),
      ('tenant','admin','admin',null,'自定义管理名称','ACTION',null,null,null,'started',true,true,true,false,now()),
      ('tenant','alias',null,'tasks','自定义任务别名','PAGE','workflow/Tasks','item','/custom-tasks','custom-root',true,false,true,true,now()),
      ('unrelated','keep',null,null,'保留资源','PAGE','own/Component','item','/keep',null,true,true,true,false,now());
    INSERT INTO simpoint_ac_role_resource_grants VALUES ('approver','workflow.tasks.view'),('approver','workflow.tasks.complete'),
      ('manager','workflow.instances.view'),('tenant-role','tenant.custom.alias');
  `);
  const grants = sql('SELECT jsonb_agg(to_jsonb(g) ORDER BY role_id,resource_code) FROM simpoint_ac_role_resource_grants g');
  const untouched = sql("SELECT to_jsonb(r) FROM simpoint_saas_tenant_resources r WHERE tenant_id='unrelated'");
  // Refuse to update copied menus against the old, unregistered canonical manifest.
  sql("UPDATE simpoint_ac_resources SET type='ACTION' WHERE id='admin'");
  assert.throws(() => sql('BEGIN;\n' + navigation + '\nCOMMIT;'));
  assert.equal(sql("SELECT authorization_version FROM simpoint_saas_tenants WHERE id='tenant'"), '7');
  sql("UPDATE simpoint_ac_resources SET type='PAGE' WHERE id='admin'");
  sql('BEGIN;\n' + navigation + '\nCOMMIT;');
  assert.equal(sql("SELECT count(*) FROM simpoint_saas_tenant_resources WHERE id IN ('tasks','started','alias') AND route_kind='hidden'"), '3');
  assert.equal(sql("SELECT name||'|'||path FROM simpoint_saas_tenant_resources WHERE id='started'"), '自定义事项名称|/my-custom-items');
  assert.equal(sql("SELECT name||'|'||path||'|'||parent_id||'|'||type FROM simpoint_saas_tenant_resources WHERE id='admin'"),
    '自定义管理名称|/workflow/management|root|PAGE');
  assert.equal(sql("SELECT NOT enabled AND manual_disabled AND grantable FROM simpoint_saas_tenant_resources WHERE id='alias'"), 't');
  assert.equal(sql("SELECT authorization_version FROM simpoint_saas_tenants WHERE id='tenant'"), '8');
  assert.equal(sql("SELECT to_jsonb(r) FROM simpoint_saas_tenant_resources r WHERE tenant_id='unrelated'"), untouched);
  assert.equal(sql('SELECT jsonb_agg(to_jsonb(g) ORDER BY role_id,resource_code) FROM simpoint_ac_role_resource_grants g'), grants);
  const menuRows = sql('SELECT jsonb_agg(to_jsonb(r) ORDER BY tenant_id,id) FROM simpoint_saas_tenant_resources r');
  sql('BEGIN;\n' + navigation + '\nCOMMIT;');
  assert.equal(sql('SELECT jsonb_agg(to_jsonb(r) ORDER BY tenant_id,id) FROM simpoint_saas_tenant_resources r'), menuRows);
  assert.equal(sql("SELECT authorization_version FROM simpoint_saas_tenants WHERE id='tenant'"), '8');
  assert.equal(sql('SELECT count(*) FROM simpoint_workflow_navigation_migrations'), '4');
  console.log('PASS: repeatable workflow DDL; ownership-proven legacy messages; exact read time/key/deep links; idempotence, preserved archived records and transaction rollback');
  console.log('PASS: tenant copies and aliases retire old menus; local names/paths/manual visibility and all grants preserved; promoted management route stays reachable; versions bump only once');
} finally {
  if (!/^workflow_test_[0-9a-f]{32}$/.test(schema)) throw Error('Invalid disposable schema');
  sql(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
}
