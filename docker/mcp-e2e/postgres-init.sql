CREATE ROLE mcp_reader LOGIN PASSWORD 'mcp-reader-local';
ALTER ROLE mcp_reader SET default_transaction_read_only = on;

CREATE SCHEMA acceptance;
CREATE TABLE acceptance.runtime_probe (
  id bigint PRIMARY KEY,
  message text NOT NULL
);
INSERT INTO acceptance.runtime_probe (id, message)
VALUES (1, 'open-simplepoint-postgresql-mcp-e2e-ok');

GRANT CONNECT ON DATABASE mcp_e2e TO mcp_reader;
GRANT USAGE ON SCHEMA acceptance TO mcp_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA acceptance TO mcp_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA acceptance
  GRANT SELECT ON TABLES TO mcp_reader;
