ALTER TABLE incident_analyses
    ADD COLUMN request_type VARCHAR(30),
    ADD COLUMN jenkins_job_path VARCHAR(500),
    ADD COLUMN jenkins_build_number BIGINT,
    ADD COLUMN observation_start_at TIMESTAMPTZ,
    ADD COLUMN observation_end_at TIMESTAMPTZ,
    ADD COLUMN observation_environment VARCHAR(20);
