ALTER TABLE command_interpretations
    ADD COLUMN redacted_request_text TEXT;

CREATE TABLE candidate_refinement_tasks (
    task_id VARCHAR(100) PRIMARY KEY,
    analysis_id VARCHAR(36) NOT NULL,
    candidate_id VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    failure_reason VARCHAR(1000),
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_candidate_refinement_analysis
        FOREIGN KEY (analysis_id) REFERENCES incident_analyses (analysis_id) ON DELETE CASCADE,
    CONSTRAINT uk_candidate_refinement_target UNIQUE (analysis_id, candidate_id)
);

CREATE INDEX idx_candidate_refinement_status
    ON candidate_refinement_tasks (status, updated_at);
