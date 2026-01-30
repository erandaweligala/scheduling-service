-- =========================================================
-- Database Statistics Gathering Script
-- =========================================================
-- Purpose: Gather comprehensive statistics for Oracle optimizer
-- to make intelligent query execution decisions.
--
-- CRITICAL FOR 5M+ SERVICE_INSTANCE and 10M+ BUCKET_INSTANCE:
-- Without accurate statistics, Oracle may choose inefficient
-- execution plans (full table scans instead of index access).
--
-- Schedule: Run this script:
-- 1. After initial data load
-- 2. Weekly during off-peak hours
-- 3. After bulk data operations
-- 4. When query performance degrades
-- =========================================================

-- Set timing on to monitor execution time
SET TIMING ON;
SET SERVEROUTPUT ON;

-- =========================================================
-- 1. Gather Table Statistics (Most Important)
-- =========================================================
BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for SERVICE_INSTANCE...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'SERVICE_INSTANCE',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE,  -- Also gather index statistics
        degree => 4       -- Use 4 parallel processes
    );
    DBMS_OUTPUT.PUT_LINE('SERVICE_INSTANCE statistics gathered successfully.');
END;
/

BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for BUCKET_INSTANCE...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'BUCKET_INSTANCE',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE,  -- Also gather index statistics
        degree => 4       -- Use 4 parallel processes
    );
    DBMS_OUTPUT.PUT_LINE('BUCKET_INSTANCE statistics gathered successfully.');
END;
/

BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for PLAN_TO_BUCKET...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'PLAN_TO_BUCKET',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE
    );
    DBMS_OUTPUT.PUT_LINE('PLAN_TO_BUCKET statistics gathered successfully.');
END;
/

BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for USER_ENTITY...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'USER_ENTITY',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE
    );
    DBMS_OUTPUT.PUT_LINE('USER_ENTITY statistics gathered successfully.');
END;
/

BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for PLAN...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'PLAN',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE
    );
    DBMS_OUTPUT.PUT_LINE('PLAN statistics gathered successfully.');
END;
/

BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for BUCKET...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'BUCKET',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE
    );
    DBMS_OUTPUT.PUT_LINE('BUCKET statistics gathered successfully.');
END;
/

BEGIN
    DBMS_OUTPUT.PUT_LINE('Starting statistics gathering for QOS_PROFILE...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => 'AAA',
        tabname => 'QOS_PROFILE',
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
        cascade => TRUE
    );
    DBMS_OUTPUT.PUT_LINE('QOS_PROFILE statistics gathered successfully.');
END;
/

-- =========================================================
-- 2. Gather Index Statistics (if not cascaded above)
-- =========================================================
-- Normally CASCADE => TRUE handles this, but you can run
-- separately if needed:

-- BEGIN
--     DBMS_STATS.GATHER_INDEX_STATS(
--         ownname => 'AAA',
--         indname => 'idx_service_recurring_next_expiry'
--     );
-- END;
-- /

-- =========================================================
-- 3. View Statistics Summary
-- =========================================================
SELECT
    table_name,
    num_rows,
    blocks,
    avg_row_len,
    last_analyzed,
    stale_stats
FROM user_tables
WHERE table_name IN (
    'SERVICE_INSTANCE',
    'BUCKET_INSTANCE',
    'PLAN_TO_BUCKET',
    'USER_ENTITY',
    'PLAN',
    'BUCKET',
    'QOS_PROFILE'
)
ORDER BY table_name;

-- =========================================================
-- 4. View Index Statistics
-- =========================================================
SELECT
    index_name,
    table_name,
    blevel AS tree_level,
    leaf_blocks,
    distinct_keys,
    num_rows,
    clustering_factor,
    last_analyzed,
    status
FROM user_indexes
WHERE table_name IN ('SERVICE_INSTANCE', 'BUCKET_INSTANCE')
ORDER BY table_name, index_name;

-- =========================================================
-- 5. Column Statistics (for important filter columns)
-- =========================================================
SELECT
    table_name,
    column_name,
    num_distinct,
    density,
    num_nulls,
    last_analyzed
FROM user_tab_col_statistics
WHERE table_name = 'SERVICE_INSTANCE'
  AND column_name IN ('RECURRING_FLAG', 'NEXT_CYCLE_START_DATE', 'EXPIRY_DATE', 'STATUS')
ORDER BY column_name;

SELECT
    table_name,
    column_name,
    num_distinct,
    density,
    num_nulls,
    last_analyzed
FROM user_tab_col_statistics
WHERE table_name = 'BUCKET_INSTANCE'
  AND column_name IN ('SERVICE_ID', 'BUCKET_ID', 'BUCKET_TYPE', 'EXPIRATION')
ORDER BY column_name;

-- =========================================================
-- 6. Lock Statistics (Optional - only if stats are volatile)
-- =========================================================
-- This prevents automatic stats gathering from overwriting
-- manually gathered stats. Use cautiously.

-- BEGIN
--     DBMS_STATS.LOCK_TABLE_STATS(ownname => 'AAA', tabname => 'SERVICE_INSTANCE');
--     DBMS_STATS.LOCK_TABLE_STATS(ownname => 'AAA', tabname => 'BUCKET_INSTANCE');
-- END;
-- /

-- =========================================================
-- 7. Set Statistics Gathering Preferences (Optional)
-- =========================================================
-- Configure automatic statistics gathering preferences

BEGIN
    DBMS_STATS.SET_TABLE_PREFS(
        ownname => 'AAA',
        tabname => 'SERVICE_INSTANCE',
        pname => 'ESTIMATE_PERCENT',
        pvalue => 'DBMS_STATS.AUTO_SAMPLE_SIZE'
    );

    DBMS_STATS.SET_TABLE_PREFS(
        ownname => 'AAA',
        tabname => 'SERVICE_INSTANCE',
        pname => 'DEGREE',
        pvalue => '4'  -- Parallel degree
    );

    DBMS_STATS.SET_TABLE_PREFS(
        ownname => 'AAA',
        tabname => 'BUCKET_INSTANCE',
        pname => 'ESTIMATE_PERCENT',
        pvalue => 'DBMS_STATS.AUTO_SAMPLE_SIZE'
    );

    DBMS_STATS.SET_TABLE_PREFS(
        ownname => 'AAA',
        tabname => 'BUCKET_INSTANCE',
        pname => 'DEGREE',
        pvalue => '4'  -- Parallel degree
    );

    DBMS_OUTPUT.PUT_LINE('Statistics preferences set successfully.');
END;
/

DBMS_OUTPUT.PUT_LINE('===========================================');
DBMS_OUTPUT.PUT_LINE('Statistics gathering completed successfully!');
DBMS_OUTPUT.PUT_LINE('===========================================');

SET TIMING OFF;
