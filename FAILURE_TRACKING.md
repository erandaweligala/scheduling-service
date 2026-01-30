# Service Processing Failure Tracking

## Overview

The failure tracking system captures and persists detailed information about failed service processing attempts during recurrent service reactivation. This enables monitoring, analysis, and potential retry mechanisms.

## Database Schema

### Table: SERVICE_PROCESSING_FAILURE

Stores failure details for each service that fails during batch processing.

| Column | Type | Description |
|--------|------|-------------|
| ID | NUMBER(19) | Primary key |
| SERVICE_INSTANCE_ID | NUMBER(19) | Reference to the failed service instance |
| USERNAME | VARCHAR2(64) | Username associated with the service |
| PLAN_ID | VARCHAR2(64) | Plan identifier |
| PLAN_NAME | VARCHAR2(128) | Plan name for reference |
| ERROR_TYPE | VARCHAR2(100) | Exception class name |
| ERROR_MESSAGE | VARCHAR2(4000) | Exception message |
| STACK_TRACE | VARCHAR2(4000) | Full exception stack trace |
| RETRY_COUNT | NUMBER(10) | Number of retry attempts |
| PROCESSING_STATUS | VARCHAR2(50) | Status: FAILED, PENDING_RETRY, RESOLVED |
| FAILURE_DATE | TIMESTAMP | When the failure occurred |
| LAST_RETRY_DATE | TIMESTAMP | When last retry was attempted |
| RESOLVED_DATE | TIMESTAMP | When the failure was resolved |
| BATCH_ID | VARCHAR2(100) | Unique identifier for the batch run |
| ADDITIONAL_INFO | VARCHAR2(1000) | Additional context |

## Setup

1. **Run the database migration script:**
   ```sql
   @src/main/resources/db/create-service-processing-failure-table.sql
   ```

2. **Verify the table and sequence were created:**
   ```sql
   SELECT COUNT(*) FROM SERVICE_PROCESSING_FAILURE;
   SELECT SERVICE_PROCESSING_FAILURE_SEQ.NEXTVAL FROM DUAL;
   ```

## How It Works

### Automatic Failure Tracking

When a service fails during the `reactivateExpiredRecurrentServices()` batch processing:

1. The exception is caught and logged
2. Failure details are saved to the `SERVICE_PROCESSING_FAILURE` table
3. Each failure record includes:
   - Service instance details
   - Username
   - Plan information
   - Error type and message
   - Full stack trace
   - Batch ID for tracking
   - Processing status (initially "FAILED")

### Failure Scenarios Tracked

The system tracks failures in these scenarios:

1. **User not found** - When the user entity cannot be retrieved
2. **Plan not found** - When the plan associated with the service is missing
3. **Processing exceptions** - Any exception during service processing (quota provisioning, cycle updates, etc.)

### Example Failure Record

```json
{
  "id": 1,
  "serviceInstanceId": 12345,
  "username": "user@example.com",
  "planId": "PLAN_001",
  "planName": "Premium Plan",
  "errorType": "AAAException",
  "errorMessage": "Failed to provision quota: bucket not found",
  "stackTrace": "com.axonect.aee.template.baseapp.domain.exception.AAAException...",
  "retryCount": 0,
  "processingStatus": "FAILED",
  "failureDate": "2026-01-30T10:30:45",
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "additionalInfo": "ServiceId: 12345, NextCycleStart: 2026-02-01T00:00:00"
}
```

## Querying Failures

### Get all failures for a specific batch
```sql
SELECT * FROM SERVICE_PROCESSING_FAILURE
WHERE BATCH_ID = 'your-batch-id'
ORDER BY FAILURE_DATE DESC;
```

### Get all failed services for a user
```sql
SELECT * FROM SERVICE_PROCESSING_FAILURE
WHERE USERNAME = 'user@example.com'
AND PROCESSING_STATUS = 'FAILED'
ORDER BY FAILURE_DATE DESC;
```

### Get failures within a date range
```sql
SELECT * FROM SERVICE_PROCESSING_FAILURE
WHERE FAILURE_DATE BETWEEN TO_TIMESTAMP('2026-01-01', 'YYYY-MM-DD')
                       AND TO_TIMESTAMP('2026-01-31', 'YYYY-MM-DD')
ORDER BY FAILURE_DATE DESC;
```

### Get failures ready for retry (example)
```sql
SELECT * FROM SERVICE_PROCESSING_FAILURE
WHERE PROCESSING_STATUS = 'PENDING_RETRY'
AND RETRY_COUNT < 3
ORDER BY FAILURE_DATE ASC;
```

### Get failure statistics by error type
```sql
SELECT ERROR_TYPE, COUNT(*) as FAILURE_COUNT
FROM SERVICE_PROCESSING_FAILURE
WHERE FAILURE_DATE >= TRUNC(SYSDATE) - 7
GROUP BY ERROR_TYPE
ORDER BY FAILURE_COUNT DESC;
```

## Integration with Monitoring

### Batch Processing Logs

Each batch run generates a unique `batchId` that is logged at the start:

```
Reactivate expired recurrent services started with batch ID: 550e8400-e29b-41d4-a716-446655440000
```

At the end of processing, success and failure counts are reported:

```
Reactivate expired recurrent services Completed. Success: 9850, Failures: 150
```

### Failure Isolation

- Each service processes in its own transaction (`REQUIRES_NEW` propagation)
- Failed services rollback individually without affecting successful ones
- Failure tracking also uses `REQUIRES_NEW` to ensure failure records are committed even if the main batch has issues

## Future Enhancements

### Potential Retry Mechanism

```java
@Scheduled(cron = "0 0 2 * * ?")  // Daily at 2 AM
public void retryFailedServices() {
    List<ServiceProcessingFailure> pendingRetries =
        serviceProcessingFailureRepository
            .findByProcessingStatusAndRetryCountLessThan("PENDING_RETRY", 3);

    for (ServiceProcessingFailure failure : pendingRetries) {
        // Attempt to reprocess the service
        // Update retry count and status
    }
}
```

### Alerting

Set up alerts based on failure thresholds:

```sql
-- Alert if more than 100 failures in the last hour
SELECT COUNT(*) as RECENT_FAILURES
FROM SERVICE_PROCESSING_FAILURE
WHERE FAILURE_DATE >= SYSTIMESTAMP - INTERVAL '1' HOUR
AND PROCESSING_STATUS = 'FAILED';
```

## Maintenance

### Archive Old Failures

To prevent table growth, consider archiving old resolved failures:

```sql
-- Archive failures older than 90 days
CREATE TABLE SERVICE_PROCESSING_FAILURE_ARCHIVE AS
SELECT * FROM SERVICE_PROCESSING_FAILURE
WHERE PROCESSING_STATUS = 'RESOLVED'
AND RESOLVED_DATE < SYSTIMESTAMP - INTERVAL '90' DAY;

-- Delete archived records
DELETE FROM SERVICE_PROCESSING_FAILURE
WHERE PROCESSING_STATUS = 'RESOLVED'
AND RESOLVED_DATE < SYSTIMESTAMP - INTERVAL '90' DAY;

COMMIT;
```

## Support

For issues or questions about failure tracking, contact the development team or refer to the main application documentation.
