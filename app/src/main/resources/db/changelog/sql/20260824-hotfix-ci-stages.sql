CREATE TABLE incident_hotfix_ci_stages (
    hotfix_id VARCHAR(36) NOT NULL REFERENCES incident_hotfixes ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    stage_id VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    start_time_millis BIGINT NOT NULL,
    duration_millis BIGINT NOT NULL,
    detail TEXT,
    PRIMARY KEY (hotfix_id, item_order)
);
