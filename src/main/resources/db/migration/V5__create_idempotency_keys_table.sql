CREATE TABLE idempotency_keys
(
    idempotency_key VARCHAR(100) NOT NULL PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT          NOT NULL,
    response_body   NVARCHAR(MAX) NOT NULL,
    created_at      DATETIME2    NOT NULL,
    expires_at      DATETIME2    NOT NULL
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);