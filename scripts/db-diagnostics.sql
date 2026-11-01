-- =========================================================================
-- CORE BANKING DIAGNOSTIC SUITE
-- Run these queries inside the tsql client to verify local ledger health.
-- =========================================================================

-- 1. Check current connection context setup
USE finflow;
GO

-- 2. Inspect active routing account balances
SELECT account_number, owner_name, balance, version
FROM accounts;
GO

-- 3. Audit structural ledger history
SELECT source_account_number, target_account_number, amount, timestamp, status
FROM transactions;
GO
