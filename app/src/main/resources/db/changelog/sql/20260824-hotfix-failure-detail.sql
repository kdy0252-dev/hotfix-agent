ALTER TABLE incident_hotfixes
    ADD COLUMN failure_stage VARCHAR(50),
    ADD COLUMN failure_code VARCHAR(100),
    ADD COLUMN review_branch_url TEXT,
    ADD COLUMN active_stage VARCHAR(50),
    ADD COLUMN active_message TEXT;

ALTER TABLE incident_hotfix_verification_stages
    ADD COLUMN summary TEXT;
