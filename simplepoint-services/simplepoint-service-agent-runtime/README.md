# SimplePoint Agent Runtime

Independent worker process for durable Agent executions. It claims fenced work
from PostgreSQL, invokes pinned model definitions, and can only dispatch the
exact Skill versions captured by an immutable Agent version.

The service does not expose Agent management APIs or load the AI workbench
frontend. Horizontal replicas coordinate through database leases and
skip-locked claims.
