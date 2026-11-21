UPDATE simpoint_app_definitions app
SET status = CASE WHEN EXISTS (
    SELECT 1 FROM simpoint_app_pages page
    WHERE page.application_id = app.id AND page.tenant_id = app.tenant_id
      AND page.status = 'OFFLINING'
  ) THEN 'OFFLINING' ELSE 'OFFLINE' END,
  updated_at = CURRENT_TIMESTAMP
WHERE app.status = 'PUBLISHED'
  AND EXISTS (
    SELECT 1 FROM simpoint_app_pages page
    WHERE page.application_id = app.id AND page.tenant_id = app.tenant_id
      AND page.status IN ('OFFLINING', 'OFFLINE')
  )
  AND NOT EXISTS (
    SELECT 1 FROM simpoint_app_pages page
    WHERE page.application_id = app.id AND page.tenant_id = app.tenant_id
      AND page.status = 'PUBLISHED'
  );
