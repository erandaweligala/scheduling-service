-- Performance Optimization Indexes
-- These indexes improve query performance for the scheduling service

-- =========================================================
-- SERVICE_INSTANCE Table Indexes
-- =========================================================

-- Composite index for the main query used in recurrent service activation
-- Covers: RECURRING_FLAG, NEXT_CYCLE_START_DATE, EXPIRY_DATE
CREATE INDEX IF NOT EXISTS idx_service_recurring_next_expiry
ON SERVICE_INSTANCE(RECURRING_FLAG, NEXT_CYCLE_START_DATE, EXPIRY_DATE);

-- Index for username lookups (used in batch loading)
CREATE INDEX IF NOT EXISTS idx_service_username
ON SERVICE_INSTANCE(USERNAME);

-- Index for plan ID lookups (used frequently in queries)
CREATE INDEX IF NOT EXISTS idx_service_plan_id
ON SERVICE_INSTANCE(PLAN_ID);

-- =========================================================
-- BUCKET_INSTANCE Table Indexes
-- =========================================================

-- Index for service ID foreign key (heavily used in joins)
CREATE INDEX IF NOT EXISTS idx_bucket_instance_service_id
ON BUCKET_INSTANCE(SERVICE_ID);

-- Index for expiration date (used in deletion and filtering)
CREATE INDEX IF NOT EXISTS idx_bucket_instance_expiration
ON BUCKET_INSTANCE(EXPIRATION);

-- Index for bucket ID lookups
CREATE INDEX IF NOT EXISTS idx_bucket_instance_bucket_id
ON BUCKET_INSTANCE(BUCKET_ID);

-- Composite index for bucket type and expiration (used in carry forward logic)
CREATE INDEX IF NOT EXISTS idx_bucket_instance_type_expiration
ON BUCKET_INSTANCE(BUCKET_TYPE, EXPIRATION);

-- =========================================================
-- PLAN_TO_BUCKET Table Indexes
-- =========================================================

-- Index for plan ID foreign key (used in batch loading quota details)
CREATE INDEX IF NOT EXISTS idx_plan_to_bucket_plan_id
ON PLAN_TO_BUCKET(PLAN_ID);

-- Index for bucket ID (used in lookups)
CREATE INDEX IF NOT EXISTS idx_plan_to_bucket_bucket_id
ON PLAN_TO_BUCKET(BUCKET_ID);

-- =========================================================
-- USER_ENTITY Table Indexes
-- =========================================================

-- Index for username lookups (primary search key)
CREATE INDEX IF NOT EXISTS idx_user_username
ON USER_ENTITY(USERNAME);

-- Index for status filtering
CREATE INDEX IF NOT EXISTS idx_user_status
ON USER_ENTITY(STATUS);

-- =========================================================
-- PLAN Table Indexes
-- =========================================================

-- Index for plan ID lookups (unique but helpful for foreign key joins)
CREATE INDEX IF NOT EXISTS idx_plan_plan_id
ON PLAN(PLAN_ID);

-- Index for recurring flag filtering
CREATE INDEX IF NOT EXISTS idx_plan_recurring_flag
ON PLAN(RECURRING_FLAG);

-- =========================================================
-- Additional Performance Indexes for 5M+ / 10M+ Records
-- =========================================================

-- Composite index for service filtering and joining
CREATE INDEX IF NOT EXISTS idx_service_status_recurring
ON SERVICE_INSTANCE(STATUS, RECURRING_FLAG);

-- Index for bucket balance queries (carry forward calculations)
CREATE INDEX IF NOT EXISTS idx_bucket_balance
ON BUCKET_INSTANCE(CURRENT_BALANCE, BUCKET_TYPE);

-- Composite index for service-plan lookups
CREATE INDEX IF NOT EXISTS idx_service_plan_status
ON SERVICE_INSTANCE(PLAN_ID, STATUS);

-- =========================================================
-- Statistics Update (Oracle specific)
-- =========================================================
-- After creating indexes, update table statistics for optimal query planning
-- Execute these manually or via scheduled job:

-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'SERVICE_INSTANCE');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'BUCKET_INSTANCE');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'PLAN_TO_BUCKET');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'USER_ENTITY');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'PLAN');

-- =========================================================
-- Advanced Oracle Performance Tuning
-- =========================================================
-- These should be executed by DBA for optimal performance with 5M+ records

-- 1. Enable parallel query execution for large table scans
-- ALTER TABLE SERVICE_INSTANCE PARALLEL 4;
-- ALTER TABLE BUCKET_INSTANCE PARALLEL 4;

-- 2. Compute extended statistics for correlated columns
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'SERVICE_INSTANCE', method_opt => 'FOR ALL COLUMNS SIZE AUTO');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'BUCKET_INSTANCE', method_opt => 'FOR ALL COLUMNS SIZE AUTO');

-- 3. Set optimizer mode for better performance
-- ALTER SESSION SET optimizer_mode = FIRST_ROWS_100;

-- 4. Consider table partitioning for very large datasets (10M+ records)
-- Partition BUCKET_INSTANCE by SERVICE_ID range or by EXPIRATION date
-- Example (commented out - requires DBA and careful planning):
-- CREATE TABLE BUCKET_INSTANCE_PARTITIONED (
--     ... all columns ...
-- ) PARTITION BY RANGE (EXPIRATION) (
--     PARTITION p_2024 VALUES LESS THAN (TO_DATE('2025-01-01', 'YYYY-MM-DD')),
--     PARTITION p_2025 VALUES LESS THAN (TO_DATE('2026-01-01', 'YYYY-MM-DD')),
--     PARTITION p_future VALUES LESS THAN (MAXVALUE)
-- );

-- 5. Monitor index usage and fragmentation
-- SELECT index_name, tablespace_name, blevel, leaf_blocks, num_rows
-- FROM user_indexes
-- WHERE table_name IN ('SERVICE_INSTANCE', 'BUCKET_INSTANCE')
-- ORDER BY table_name, index_name;

-- 6. Rebuild indexes if fragmented (run periodically)
-- ALTER INDEX idx_service_recurring_next_expiry REBUILD ONLINE;
-- ALTER INDEX idx_bucket_instance_service_id REBUILD ONLINE;

-- =========================================================
-- Query Execution Plan Analysis
-- =========================================================
-- Use these to verify index usage:

-- EXPLAIN PLAN FOR
-- SELECT * FROM SERVICE_INSTANCE
-- WHERE RECURRING_FLAG = 1
--   AND NEXT_CYCLE_START_DATE >= SYSDATE
--   AND NEXT_CYCLE_START_DATE < SYSDATE + 1
--   AND EXPIRY_DATE > SYSDATE;

-- SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);
