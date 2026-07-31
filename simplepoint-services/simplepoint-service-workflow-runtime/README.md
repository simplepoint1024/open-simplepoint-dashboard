# SimplePoint Workflow Runtime

Independent worker process for durable Agent Workflow executions. Replicas
coordinate through PostgreSQL skip-locked claims and fencing tokens. Every
execution uses an immutable Workflow plan and exact published Agent/Skill
versions, persists node checkpoints and events, and supports pause/resume,
structured human tasks, timers, bounded parallel branches, and compensation.

The runtime owns no management API or workbench UI. It only advances durable
execution records and submits pinned child work to the separately scalable
Agent and Skill runtimes.
