-- New allocations capture purchase labels. Existing ID-only records retain unknown labels;
-- reading current platform names here would misrepresent them as historical purchase content.
ALTER TABLE simpoint_saas_tenant_package_instance_resources
    ADD COLUMN resource_code varchar(120),
    ADD COLUMN resource_name varchar(100);
