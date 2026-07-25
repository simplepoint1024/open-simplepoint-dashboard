# SimplePoint Notification Plugin

The notification plugin provides a durable system-notification inbox and proactive delivery
for the common service.

## Modules

- `simplepoint-plugin-notification-api`: entities, enums, repositories, services, and push contracts.
- `simplepoint-plugin-notification-repository`: JPA management, inbox, and unread queries.
- `simplepoint-plugin-notification-service`: draft/publish/revoke/duplicate lifecycle and read receipts.
- `simplepoint-plugin-notification-rest`: management APIs, user inbox APIs, SSE, and optional Redis fan-out.

## Delivery model

`simpoint_system_notifications` stores one broadcast definition with an audience selector.
`simpoint_notification_receipts` stores sparse per-user read receipts, avoiding one delivery row
per user when a global notification is published.

The database inbox is the source of truth. A committed publish, revoke, or read-state change
emits a minimal reconciliation event:

1. Redis Pub/Sub fans the event out across service instances when Redis is available.
2. Each instance filters its local SSE connections by platform, tenant, or user audience.
3. Clients reload unread count and inbox data from the database.
4. Client reconnect and periodic reconciliation cover disconnects and Pub/Sub's non-durable nature.

Notification content is rendered as plain text. Links are restricted to same-site absolute paths
or HTTPS URLs.

## Lifecycle

- Drafts can be edited, published, or deleted.
- Published notifications can be revoked.
- Revoked notifications can be deleted or copied to an independent draft.
- Copying uses a new notification ID so historical read receipts never suppress a later delivery.

## Main endpoints

- Management: `/system/notifications`
- Inbox: `/notifications/inbox`
- Unread count: `/notifications/unread-count`
- Read state: `/notifications/{id}/read`, `/notifications/read-all`
- Proactive stream: `/notifications/stream`
