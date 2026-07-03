# DNA Data-Lineage Feature Removal

The DNA data-lineage feature has been removed because the current implementation was not mature enough to keep enabled.

## Removed

- DNA menu entry: `/dna/data-lineage`
- Frontend page: `dna/platform/DataLineage`
- Frontend graph dependencies: `@xyflow/react`, `@dagrejs/dagre`
- Backend lineage node/edge entities, repositories, services, REST controllers, and tests
- `dna-data-lineage` i18n namespace and `dna.error.lineage.*` messages

## Deployment Cleanup

The project commonly uses `spring.jpa.hibernate.ddl-auto=update`, which does not remove old tables or RBAC rows. After deploying this change, run the cleanup SQL with your database client:

```bash
psql "$DATABASE_URL" -f scripts/sql/cleanup-dna-data-lineage.sql
mysql "$DATABASE_NAME" < scripts/sql/cleanup-dna-data-lineage.sql
```

The script removes initialized DNA data-lineage menu/permission metadata and drops the retired lineage tables:

- `simpoint_dna_data_lineage_edges`
- `simpoint_dna_data_lineage_nodes`

Back up the database first, then verify the DNA workbench no longer shows the data-lineage entry.

## Validation

Recommended checks:

```bash
pnpm run i18n:check
pnpm run typecheck:dna
pnpm run typecheck:mocks
pnpm run build:dna
./gradlew clean :simplepoint-services:simplepoint-service-dna:build
```
