# Unified object-storage client

Add the client module to a service to use the tenant-aware object-storage API:

```kotlin
implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-http-client"))
```

Configure the object-storage service name registered in service discovery:

```properties
simplepoint.storage.remote.service-name=common
```

Inject `ObjectStorageRemoteService` to upload, query, download, or delete objects. The client
automatically forwards the current authorization, tenant, context, locale, and cookie headers.
Uploaded object keys remain tenant-prefixed in the common service.
