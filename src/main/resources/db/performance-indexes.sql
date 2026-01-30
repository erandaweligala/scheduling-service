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
-- Statistics Update (Oracle specific)
-- =========================================================
-- After creating indexes, update table statistics for optimal query planning
-- Execute these manually or via scheduled job:

-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'SERVICE_INSTANCE');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'BUCKET_INSTANCE');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'PLAN_TO_BUCKET');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'USER_ENTITY');
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => 'AAA', tabname => 'PLAN');
