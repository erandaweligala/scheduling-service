# Bucket Expiry Notification System

## Overview

This implementation provides a comprehensive solution for sending Kafka-based notifications to users when their bucket instances are about to expire. The system uses a template-based approach with configurable notification thresholds.

## Architecture

### Components

1. **ChildTemplateTable Entity** (`AAA.CHILD_TEMPLATE_TABLE`)
   - Stores notification message templates with configurable thresholds
   - Supports multiple message types (EXPIRE, QUOTA, etc.)
   - Cached in Redis for optimal performance

2. **ExpiryNotificationService**
   - Core business logic for processing expiry notifications
   - Batch processing support for high-volume datasets
   - Replaces dynamic placeholders in message templates

3. **BucketInstanceScheduler**
   - Scheduled job running daily at 9:00 AM (configurable)
   - Processes all EXPIRE templates and sends notifications

4. **Kafka Integration**
   - Publishes notifications to `bucket-expiry-notifications` topic
   - JSON serialization for event payloads
   - Reliable delivery with retries and acknowledgments

## Database Schema

### CHILD_TEMPLATE_TABLE

```sql
CREATE TABLE AAA.CHILD_TEMPLATE_TABLE (
    ID NUMBER(19,0) PRIMARY KEY,
    CREATED_AT TIMESTAMP,
    DAYS_TO_EXPIRE NUMBER(10,0),
    MESSAGE_CONTENT CLOB,
    MESSAGE_TYPE VARCHAR2(20),
    QUOTA_PERCENTAGE NUMBER(10,0),
    SUPER_TEMPLATE_ID NUMBER(19,0),
    UPDATED_AT TIMESTAMP
);
```

### Sample Data

```sql
INSERT INTO AAA.CHILD_TEMPLATE_TABLE (
    ID,
    DAYS_TO_EXPIRE,
    MESSAGE_CONTENT,
    MESSAGE_TYPE,
    CREATED_AT,
    UPDATED_AT
) VALUES (
    1,
    2,
    'Your plan {PLAN_NAME} will expire in {DAYS_TO_EXPIRE} days on {DATE_OF_EXPIRY}. Please renew to continue services.',
    'EXPIRE',
    SYSTIMESTAMP,
    SYSTIMESTAMP
);

INSERT INTO AAA.CHILD_TEMPLATE_TABLE (
    ID,
    DAYS_TO_EXPIRE,
    MESSAGE_CONTENT,
    MESSAGE_TYPE,
    CREATED_AT,
    UPDATED_AT
) VALUES (
    2,
    7,
    'Reminder: Your plan {PLAN_NAME} will expire in {DAYS_TO_EXPIRE} days on {DATE_OF_EXPIRY}. Renew now to avoid service interruption.',
    'EXPIRE',
    SYSTIMESTAMP,
    SYSTIMESTAMP
);
```

## How It Works

### Notification Flow

1. **Scheduled Execution** (Daily at 9:00 AM)
   - Scheduler triggers `ExpiryNotificationService.processExpiryNotifications()`

2. **Template Processing**
   - Fetches all templates with `MESSAGE_TYPE = 'EXPIRE'` from cache/database
   - Each template specifies `DAYS_TO_EXPIRE` threshold

3. **Date Calculation**
   - For each template, calculates target expiry date
   - Example: Today = 2026-01-28, DAYS_TO_EXPIRE = 2 → Target = 2026-01-30

4. **Bucket Lookup**
   - Queries `BUCKET_INSTANCE` table for buckets expiring on target date
   - Uses indexed queries for optimal performance with millions of records

5. **User Data Retrieval**
   - Gets `ServiceInstance` for username and plan name
   - All data retrieved from cache where possible

6. **Message Building**
   - Replaces dynamic placeholders:
     - `{PLAN_NAME}` → Actual plan name
     - `{DATE_OF_EXPIRY}` → Expiry date (yyyy-MM-dd format)
     - `{DAYS_TO_EXPIRE}` → Number of days until expiry

7. **Kafka Publishing**
   - Sends notification event to `bucket-expiry-notifications` topic
   - Username used as Kafka message key for partitioning
   - JSON payload with all notification details

### Example Scenario

**Setup:**
- Template: DAYS_TO_EXPIRE = 2
- Today's Date: 2026-01-28
- Bucket: expires on 2026-01-30
- User: john.doe
- Plan: Premium Plan

**Result:**
```json
{
  "username": "john.doe",
  "service_id": 12345,
  "bucket_instance_id": 67890,
  "bucket_id": "DATA_10GB",
  "plan_name": "Premium Plan",
  "date_of_expiry": "2026-01-30 23:59:59",
  "days_to_expire": 2,
  "message": "Your plan Premium Plan will expire in 2 days on 2026-01-30. Please renew to continue services.",
  "message_type": "EXPIRE",
  "template_id": 1,
  "notification_time": "2026-01-28 09:00:00",
  "current_balance": 5368709120,
  "initial_balance": 10737418240
}
```

## Configuration

### application.yml

```yaml
# Expiry Notification Configuration
expiry-notification:
  batch-size: 100                    # Batch size for processing
  schedule: "0 0 9 * * ?"           # Daily at 9:00 AM

# Kafka Configuration
spring.kafka:
  bootstrap-servers: localhost:9092
  producer:
    retries: 3
    acks: all
    batch-size: 16384
    linger-ms: 10
    buffer-memory: 33554432

# Kafka Topics
kafka.topic:
  bucket-expiry-notification: bucket-expiry-notifications
```

### Cron Schedule Format

- `"0 0 9 * * ?"` - Daily at 9:00 AM
- `"0 0 */6 * * ?"` - Every 6 hours
- `"0 0 0 * * ?"` - Daily at midnight

## File Structure

```
src/main/java/com/axonect/aee/template/baseapp/
├── application/
│   ├── config/
│   │   ├── KafkaConfig.java                    # Kafka producer configuration
│   │   └── RedisConfig.java                    # Cache configuration (updated)
│   └── repository/
│       ├── ChildTemplateTableRepository.java   # Template repository
│       └── BucketInstanceRepository.java       # Bucket queries (updated)
├── domain/
│   ├── entities/
│   │   ├── dto/
│   │   │   └── BucketExpiryNotification.java   # Kafka event payload
│   │   └── repo/
│   │       └── ChildTemplateTable.java         # Template entity
│   └── service/
│       ├── ExpiryNotificationService.java      # Core notification logic
│       └── BucketInstanceScheduler.java        # Scheduled jobs (updated)
└── resources/
    └── application.yml                          # Configuration (updated)
```

## Key Features

### Performance Optimizations

1. **Redis Caching**
   - Template configurations cached for 6 hours
   - Reduces database load for frequently accessed data

2. **Batch Processing**
   - Processes bucket instances in batches of 100
   - Handles millions of records efficiently

3. **Database Indexing**
   - Uses Oracle query hints for index optimization
   - Leverages `idx_bucket_instance_expiration` index

4. **Kafka Batching**
   - Configurable batch size and linger time
   - Compression enabled (snappy) for network efficiency

### Reliability Features

1. **Transaction Management**
   - Read-only transactions for data consistency
   - No impact on production data

2. **Error Handling**
   - Continues processing even if individual notifications fail
   - Comprehensive logging for troubleshooting

3. **Kafka Reliability**
   - Idempotent producer for exactly-once semantics
   - Acknowledgment from all replicas (acks=all)
   - Automatic retries on failure

### Monitoring & Logging

```
INFO  - Starting scheduled expiry notification processing
INFO  - Found 2 EXPIRE templates to process
INFO  - Processing template ID: 1 with DAYS_TO_EXPIRE: 2
INFO  - Looking for bucket instances expiring on: 2026-01-30
INFO  - Processing page 0 with 100 bucket instances
INFO  - Notification sent for username: john.doe, plan: Premium Plan, expires on: 2026-01-30, days remaining: 2
INFO  - Template ID 1 processing completed. Sent 100 notifications
INFO  - Successfully completed expiry notification processing. Total notifications sent: 250
```

## Testing

### Manual Testing

1. **Insert Test Template**
```sql
INSERT INTO AAA.CHILD_TEMPLATE_TABLE (
    ID, DAYS_TO_EXPIRE, MESSAGE_CONTENT, MESSAGE_TYPE, CREATED_AT, UPDATED_AT
) VALUES (
    999,
    0,  -- Notify today for buckets expiring today
    'URGENT: Your plan {PLAN_NAME} expires TODAY ({DATE_OF_EXPIRY}). Renew immediately!',
    'EXPIRE',
    SYSTIMESTAMP,
    SYSTIMESTAMP
);
```

2. **Trigger Manually** (via REST endpoint - optional)
```java
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private ExpiryNotificationService expiryNotificationService;

    @PostMapping("/expiry/trigger")
    public ResponseEntity<Map<String, Object>> triggerExpiryNotifications() {
        int count = expiryNotificationService.processExpiryNotifications();
        return ResponseEntity.ok(Map.of("notificationsSent", count));
    }
}
```

3. **Consume Kafka Messages**
```bash
# Start Kafka consumer to view messages
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic bucket-expiry-notifications \
  --from-beginning \
  --property print.key=true \
  --property print.value=true
```

## Dynamic Field Support

### Supported Placeholders

| Placeholder | Description | Example Value |
|------------|-------------|---------------|
| `{PLAN_NAME}` | Name of the plan | Premium Plan |
| `{DATE_OF_EXPIRY}` | Expiry date (yyyy-MM-dd) | 2026-01-30 |
| `{DAYS_TO_EXPIRE}` | Days until expiry | 2 |

### Example Messages

```
Your plan {PLAN_NAME} will expire in {DAYS_TO_EXPIRE} days on {DATE_OF_EXPIRY}. Please renew to continue services.
→ Your plan Premium Plan will expire in 2 days on 2026-01-30. Please renew to continue services.

ALERT: {PLAN_NAME} expires on {DATE_OF_EXPIRY} ({DAYS_TO_EXPIRE} days remaining). Act now!
→ ALERT: Premium Plan expires on 2026-01-30 (2 days remaining). Act now!
```

## Deployment

### Prerequisites

1. Kafka cluster running and accessible
2. Redis server running (for caching)
3. Oracle database with CHILD_TEMPLATE_TABLE created
4. Proper indexes on BUCKET_INSTANCE table

### Configuration Steps

1. Update `application.yml` with your Kafka broker addresses
2. Configure Redis connection settings
3. Set appropriate cron schedule for your timezone
4. Adjust batch sizes based on your data volume

### Dependencies Added

```xml
<!-- Kafka Support -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

## Troubleshooting

### Common Issues

1. **No notifications sent**
   - Check if templates exist in CHILD_TEMPLATE_TABLE with MESSAGE_TYPE='EXPIRE'
   - Verify bucket instances have future expiry dates matching the criteria
   - Check logs for errors

2. **Kafka connection errors**
   - Verify Kafka broker is running and accessible
   - Check bootstrap-servers configuration
   - Ensure topic exists (auto-created by default)

3. **Cache not working**
   - Verify Redis is running
   - Check Redis connection settings in application.yml
   - Look for cache-related errors in logs

### Debug Mode

Enable debug logging:
```yaml
logging:
  level:
    com.axonect.aee.template.baseapp.domain.service.ExpiryNotificationService: DEBUG
```

## Future Enhancements

1. **Email Integration**: Add email notifications alongside Kafka events
2. **SMS Support**: Integrate SMS gateway for critical notifications
3. **User Preferences**: Allow users to configure notification preferences
4. **Quota Notifications**: Extend to support quota-based notifications using QUOTA_PERCENTAGE
5. **Notification History**: Track sent notifications in database for audit trail
6. **A/B Testing**: Support multiple message templates and test effectiveness

## API Reference

### ExpiryNotificationService

```java
public int processExpiryNotifications()
```
- Processes all EXPIRE templates and sends notifications
- Returns: Total number of notifications sent
- Throws: RuntimeException if processing fails

### Kafka Event Format

Topic: `bucket-expiry-notifications`
Key: `username` (String)
Value: `BucketExpiryNotification` (JSON)

```json
{
  "username": "string",
  "user_id": number,
  "service_id": number,
  "bucket_instance_id": number,
  "bucket_id": "string",
  "plan_name": "string",
  "date_of_expiry": "yyyy-MM-dd HH:mm:ss",
  "days_to_expire": number,
  "message": "string",
  "message_type": "string",
  "template_id": number,
  "notification_time": "yyyy-MM-dd HH:mm:ss",
  "current_balance": number,
  "initial_balance": number
}
```

## Support

For issues or questions, contact the development team or check the logs in:
- `/var/log/scheduling-service/application.log`
