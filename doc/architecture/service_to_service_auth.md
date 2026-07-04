# Service-to-Service Authentication

SimplePoint service-to-service calls use `simplepoint-service-router` with OAuth2
client credentials.

## Flow

1. A service-router consumer resolves a provider service through Consul metadata.
2. The consumer requests an access token from the authorization service by using
   its own OAuth2 client id and secret.
3. The consumer invokes `POST /_simplepoint/service-router/invoke` on the
   provider with `Authorization: Bearer <token>`.
4. The provider validates the JWT and requires `SCOPE_service-router.invoke`.
5. The service-router dispatcher invokes the local `@RemoteProvider` bean.

## Configuration

Use OAuth2 mode for normal deployments:

```properties
simplepoint.service-router.internal-auth.mode=oauth2
simplepoint.service-router.internal-auth.oauth2.token-uri=http://authorization:9000/oauth2/token
simplepoint.service-router.internal-auth.oauth2.client-id=simplepoint-service-common
simplepoint.service-router.internal-auth.oauth2.client-secret=${SIMPLEPOINT_COMMON_SERVICE_CLIENT_SECRET}
simplepoint.service-router.internal-auth.oauth2.scopes[0]=service-router.invoke
simplepoint.service-router.internal-auth.oauth2.required-authority=SCOPE_service-router.invoke
```

The old shared header token remains available only for compatibility by setting
`simplepoint.service-router.internal-auth.mode=shared-token` and configuring
`simplepoint.service-router.internal-auth.token`.

## Operational Notes

Each deployed service should use a distinct OAuth2 client id and secret. Local
Docker defaults are development-only values and should be replaced in production.
