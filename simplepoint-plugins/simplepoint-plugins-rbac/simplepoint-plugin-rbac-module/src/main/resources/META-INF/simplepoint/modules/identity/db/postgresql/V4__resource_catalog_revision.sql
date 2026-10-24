CREATE TABLE simpoint_resource_catalog_revision (
    id varchar(32) PRIMARY KEY CHECK (id = 'catalog'),
    revision bigint NOT NULL CHECK (revision >= 0)
);

INSERT INTO simpoint_resource_catalog_revision (id, revision) VALUES ('catalog', 0);
