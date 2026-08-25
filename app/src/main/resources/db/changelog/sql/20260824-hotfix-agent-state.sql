CREATE TABLE command_interpretations (
    interpretation_id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_body_hash VARCHAR(255) NOT NULL,
    request_digest VARCHAR(255) NOT NULL,
    redacted_preview TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(40) NOT NULL,
    intent VARCHAR(40),
    command_hash VARCHAR(255),
    rejection_code VARCHAR(100),
    rejection_message TEXT,
    policy_repository VARCHAR(255) NOT NULL,
    policy_service VARCHAR(100) NOT NULL,
    policy_delivery VARCHAR(100) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    job_path VARCHAR(500),
    build_number BIGINT,
    observation_start_at TIMESTAMPTZ,
    observation_end_at TIMESTAMPTZ,
    environment VARCHAR(20),
    analysis_id VARCHAR(36),
    analysis_version BIGINT,
    candidate_id VARCHAR(36),
    hotfix_id VARCHAR(36),
    source_type VARCHAR(30),
    source_branch VARCHAR(500),
    source_pull_request_number BIGINT
);

CREATE TABLE command_interpretation_missing_fields (
    interpretation_id VARCHAR(36) NOT NULL REFERENCES command_interpretations ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    value VARCHAR(255) NOT NULL,
    PRIMARY KEY (interpretation_id, item_order)
);

CREATE TABLE command_interpretation_questions (
    interpretation_id VARCHAR(36) NOT NULL REFERENCES command_interpretations ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (interpretation_id, item_order)
);

CREATE TABLE command_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    interpretation_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(255) NOT NULL,
    resource_id VARCHAR(255),
    status VARCHAR(80) NOT NULL,
    status_url TEXT,
    executed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE command_execution_items (
    execution_id VARCHAR(36) NOT NULL REFERENCES command_executions ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    value VARCHAR(255) NOT NULL,
    PRIMARY KEY (execution_id, item_order)
);

CREATE TABLE incident_analyses (
    analysis_id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(255) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_branch VARCHAR(500),
    source_pull_request_id BIGINT,
    source_commit VARCHAR(100),
    destination_branch VARCHAR(500),
    source_provenance VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(50) NOT NULL,
    failure_reason TEXT,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE incident_candidates (
    candidate_id VARCHAR(36) PRIMARY KEY,
    analysis_id VARCHAR(36) NOT NULL REFERENCES incident_analyses ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    title TEXT NOT NULL,
    root_cause TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    eligibility VARCHAR(40) NOT NULL,
    fix_summary TEXT NOT NULL,
    verification_summary TEXT NOT NULL,
    UNIQUE (analysis_id, item_order)
);

CREATE TABLE incident_candidate_source_locations (
    candidate_id VARCHAR(36) NOT NULL REFERENCES incident_candidates ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (candidate_id, item_order)
);

CREATE TABLE incident_candidate_evidence_refs (
    candidate_id VARCHAR(36) NOT NULL REFERENCES incident_candidates ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (candidate_id, item_order)
);

CREATE TABLE incident_candidate_counter_evidence (
    candidate_id VARCHAR(36) NOT NULL REFERENCES incident_candidates ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (candidate_id, item_order)
);

CREATE TABLE incident_hotfixes (
    hotfix_id VARCHAR(36) PRIMARY KEY,
    analysis_id VARCHAR(36) NOT NULL,
    candidate_id VARCHAR(36) NOT NULL,
    schema_version INTEGER NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    branch_name VARCHAR(500),
    changed_files INTEGER NOT NULL,
    changed_lines INTEGER NOT NULL,
    focused_attempts INTEGER NOT NULL,
    base_commit VARCHAR(100),
    patch_commit VARCHAR(100),
    jenkinsfile_path VARCHAR(500),
    jenkinsfile_sha256 VARCHAR(100),
    jenkinsfile_profile_version INTEGER,
    human_review_reason TEXT,
    draft_pull_request_url TEXT,
    ci_build_url TEXT,
    ci_result VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE incident_hotfix_verification_stages (
    hotfix_id VARCHAR(36) NOT NULL REFERENCES incident_hotfixes ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    exit_code INTEGER NOT NULL,
    required BOOLEAN NOT NULL,
    PRIMARY KEY (hotfix_id, item_order)
);
