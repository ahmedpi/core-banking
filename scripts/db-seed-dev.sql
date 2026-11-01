-- Local Dev/Test Sample Seed Accounts Setup
USE finflow;
GO

INSERT INTO accounts (id, account_number, owner_name, balance, version)
VALUES ('a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d', 'ACC123', 'Ahmed', 1000.00, 0);

INSERT INTO accounts (id, account_number, owner_name, balance, version)
VALUES ('f7e6d5c4-b3a2-10f9-8e7d-6c5b4a3f2e1d', 'ACC456', 'Mario', 500.00, 0);
GO