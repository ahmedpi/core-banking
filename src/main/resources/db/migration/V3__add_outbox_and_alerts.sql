CREATE TABLE outbox_events
(
    id             VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,                   -- e.g., 'TRANSFER'
    aggregate_id   VARCHAR(36)  NOT NULL,                   -- transaction ID
    event_type     VARCHAR(100) NOT NULL,                   -- e.g., 'TransferCompletedEvent'
    payload        NVARCHAR(MAX) NOT NULL,                  -- JSON representation
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED
    created_at     DATETIME2    NOT NULL DEFAULT SYSDATETIME(),
    published_at   DATETIME2 NULL
);

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

CREATE TABLE fraud_alerts
(
    id             VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL UNIQUE,
    risk_score     INT         NOT NULL,
    reasoning      NVARCHAR(1000) NOT NULL,
    flagged_at     DATETIME2   NOT NULL DEFAULT SYSDATETIME()
);