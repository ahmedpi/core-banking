-- 1. Add new columns with explicitly named default constraints
ALTER TABLE fraud_alerts
    ADD account_number VARCHAR(20) NOT NULL CONSTRAINT DF_fraud_alerts_account_number DEFAULT 'UNKNOWN',
        risk_level VARCHAR(20) NOT NULL CONSTRAINT DF_fraud_alerts_risk_level DEFAULT 'HIGH',
        created_at DATETIME2 NOT NULL CONSTRAINT DF_fraud_alerts_created_at DEFAULT SYSUTCDATETIME();

-- 2. Dynamically find and drop the auto-generated default constraint on flagged_at
DECLARE @ConstraintName NVARCHAR(200);

SELECT @ConstraintName = dc.name
FROM sys.default_constraints dc
         JOIN sys.columns c
              ON dc.parent_object_id = c.object_id
                  AND dc.parent_column_id = c.column_id
WHERE dc.parent_object_id = OBJECT_ID('fraud_alerts')
  AND c.name = 'flagged_at';

IF @ConstraintName IS NOT NULL
BEGIN
EXEC('ALTER TABLE fraud_alerts DROP CONSTRAINT [' + @ConstraintName + ']');
END

-- 3. Drop the old column
ALTER TABLE fraud_alerts
DROP COLUMN flagged_at;

-- 4. Create the new index
CREATE INDEX idx_fraud_alerts_account_number ON fraud_alerts (account_number);