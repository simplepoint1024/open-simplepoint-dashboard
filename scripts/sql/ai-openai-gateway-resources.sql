-- OpenAI native gateway routing metadata; no prompt, response content, or credential storage.
-- Apply before deploying the native gateway when automatic schema updates are disabled.
CREATE TABLE IF NOT EXISTS simpoint_ai_gateway_resources (
    id varchar(64) PRIMARY KEY,
    api_key_id varchar(64) NOT NULL,
    provider_definition_id varchar(64) NOT NULL,
    model_definition_id varchar(64) NOT NULL,
    public_model_id varchar(256) NOT NULL,
    resource_type varchar(32) NOT NULL,
    upstream_id varchar(256) NOT NULL,
    invocation_id varchar(64),
    created_at timestamp with time zone NOT NULL
);
