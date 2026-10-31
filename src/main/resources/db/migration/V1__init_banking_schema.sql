-- 1. Create the Accounts Table
CREATE TABLE accounts
(
    id             VARCHAR(36) PRIMARY KEY,
    account_number VARCHAR(20)    NOT NULL UNIQUE,
    owner_name     VARCHAR(255)   NOT NULL,
    balance        DECIMAL(18, 2) NOT NULL,
    version        BIGINT         NOT NULL
);

-- 2. Create the Transactions Audit Ledger Table
CREATE TABLE transactions
(
    id                    VARCHAR(36) PRIMARY KEY,
    source_account_number VARCHAR(20)    NOT NULL,
    target_account_number VARCHAR(20)    NOT NULL,
    amount                DECIMAL(18, 2) NOT NULL,
    timestamp             DATETIME2      NOT NULL,
    status                VARCHAR(50)    NOT NULL
);