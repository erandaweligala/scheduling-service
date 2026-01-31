package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketRepository;
import com.axonect.aee.template.baseapp.application.repository.PlanRepository;
import com.axonect.aee.template.baseapp.application.repository.PlanToBucketRepository;
import com.axonect.aee.template.baseapp.application.repository.QOSProfileRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceProcessingFailureRepository;
import com.axonect.aee.template.baseapp.application.repository.UserRepository;
import com.axonect.aee.template.baseapp.domain.entities.repo.Bucket;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.Plan;
import com.axonect.aee.template.baseapp.domain.entities.repo.PlanToBucket;
import com.axonect.aee.template.baseapp.domain.entities.repo.QOSProfile;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceProcessingFailure;
import com.axonect.aee.template.baseapp.domain.entities.repo.UserEntity;
import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecurrentServiceService {

    private final UserRepository userRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final PlanRepository planRepository;
    private final PlanToBucketRepository planToBucketRepository;
    private final BucketRepository bucketRepository;
    private final QOSProfileRepository qosProfileRepository;
    private final BucketInstanceRepository bucketInstanceRepository;
    private final UserCacheService userCacheService;
    private final ServiceProcessingFailureRepository serviceProcessingFailureRepository;

    // Self-injection to enable transactional method calls via proxy
    // Using field injection to avoid circular dependency during constructor injection
    @Autowired
    @Lazy
    private RecurrentServiceService self;

    @Value("${recurrent-service.chunk-size}")
    private int chunkSize;

    public void reactivateExpiredRecurrentServices() {
        // Generate unique batch ID for this processing run
        String batchId = UUID.randomUUID().toString();
        log.info("Reactivate expired recurrent services started with batch ID: {}", batchId);

        int pageNumber = 0;
        Pageable pageable = PageRequest.of(pageNumber, chunkSize);
        Page<ServiceInstance> servicePage;

        LocalDateTime tomorrowStart = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE))
                .plusDays(1)
                .atStartOfDay();
        LocalDateTime tomorrowEnd = tomorrowStart.plusDays(1);

        int successCount = 0;
        int failureCount = 0;

        do {
            servicePage = serviceInstanceRepository.findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
                    tomorrowStart,
                    tomorrowEnd,
                    tomorrowStart,
                    pageable
            );

            List<ServiceInstance> services = servicePage.getContent();
            if (services.isEmpty()) {
                log.info("No services to process in batch (page {})", pageNumber);
                break;
            }

            log.info("Processing {} services in batch (page {})", services.size(), pageNumber);

            // BATCH LOAD: Collect all unique identifiers
            Set<String> usernames = services.stream().map(ServiceInstance::getUsername).collect(Collectors.toSet());
            Set<String> planIds = services.stream().map(ServiceInstance::getPlanId).collect(Collectors.toSet());
            Set<Long> serviceIds = services.stream().map(ServiceInstance::getId).collect(Collectors.toSet());

            // BATCH LOAD: Fetch all required data in bulk
            Map<String, UserEntity> userMap = userRepository.findByUserNameIn(usernames).stream()
                    .collect(Collectors.toMap(UserEntity::getUserName, u -> u));
            Map<String, Plan> planMap = planRepository.findByPlanIdIn(planIds).stream()
                    .collect(Collectors.toMap(Plan::getPlanId, p -> p));
            Map<Long, List<BucketInstance>> bucketInstanceMap = bucketInstanceRepository.findByServiceIdIn(serviceIds).stream()
                    .collect(Collectors.groupingBy(BucketInstance::getServiceId));
            Map<String, List<PlanToBucket>> planToBucketMap = planToBucketRepository.findByPlanIdIn(planIds).stream()
                    .collect(Collectors.groupingBy(PlanToBucket::getPlanId));

            // Collect all unique bucket IDs and QoS IDs
            Set<String> bucketIds = planToBucketMap.values().stream()
                    .flatMap(Collection::stream)
                    .map(PlanToBucket::getBucketId)
                    .collect(Collectors.toSet());

            Map<String, Bucket> bucketMap = bucketRepository.findByBucketIdIn(bucketIds).stream()
                    .collect(Collectors.toMap(Bucket::getBucketId, b -> b));

            Set<Long> qosIds = bucketMap.values().stream()
                    .map(Bucket::getQosId)
                    .collect(Collectors.toSet());

            Map<Long, QOSProfile> qosProfileMap = qosProfileRepository.findByIdIn(qosIds).stream()
                    .collect(Collectors.toMap(QOSProfile::getId, q -> q));

            // Process each service in its own transaction
            for (ServiceInstance serviceInstance : services) {
                UserEntity user = userMap.get(serviceInstance.getUsername());
                if (user == null) {
                    log.warn("User not found for service ID: {}, username: {}",
                            serviceInstance.getId(), serviceInstance.getUsername());
                    failureCount++;
                    self.saveServiceProcessingFailure(serviceInstance, null, serviceInstance.getUsername(),
                            new IllegalStateException("User not found: " + serviceInstance.getUsername()), batchId);
                    continue;
                }

                Plan plan = planMap.get(serviceInstance.getPlanId());
                if (plan == null) {
                    log.error("Plan not found: {}", serviceInstance.getPlanId());
                    failureCount++;
                    self.saveServiceProcessingFailure(serviceInstance, null, user.getUserName(),
                            new IllegalStateException("Plan not found: " + serviceInstance.getPlanId()), batchId);
                    continue;
                }

                try {
                    // Each service is processed in its own independent transaction
                    self.processServiceInstanceInTransaction(
                            serviceInstance,
                            user,
                            plan,
                            bucketInstanceMap.get(serviceInstance.getId()),
                            planToBucketMap.get(plan.getPlanId()),
                            bucketMap,
                            qosProfileMap
                    );
                    successCount++;
                    log.info("Successfully processed service {} for user {}", serviceInstance.getPlanId(), user.getUserName());
                } catch (Exception ex) {
                    failureCount++;
                    log.error("Failed to process service ID: {} for user: {}. Error: {}",
                            serviceInstance.getId(), user.getUserName(), ex.getMessage(), ex);

                    // Save failure details to database for tracking and retry
                    self.saveServiceProcessingFailure(serviceInstance, plan, user.getUserName(), ex, batchId);

                    // Continue processing other services even if this one fails
                }
            }

            pageNumber++;
            pageable = PageRequest.of(pageNumber, chunkSize);

        } while (!servicePage.isLast());

        log.info("Reactivate expired recurrent services Completed. Success: {}, Failures: {}",
                successCount, failureCount);
    }

    /**
     * Processes a single service instance in its own transaction.
     * Each service gets an independent transaction that can commit or rollback without affecting others.
     *
     * @param serviceInstance The service instance to process
     * @param user The user entity
     * @param plan The plan entity
     * @param bucketInstanceList List of bucket instances for this service
     * @param quotaDetails Quota details from plan
     * @param bucketMap Map of bucket IDs to bucket entities
     * @param qosProfileMap Map of QoS profile IDs to QoS profiles
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3600)
    public void processServiceInstanceInTransaction(
            ServiceInstance serviceInstance,
            UserEntity user,
            Plan plan,
            List<BucketInstance> bucketInstanceList,
            List<PlanToBucket> quotaDetails,
            Map<String, Bucket> bucketMap,
            Map<Long, QOSProfile> qosProfileMap) {

        log.debug("Processing service instance ID: {} in new transaction", serviceInstance.getId());

        // Update cycle management properties
        updateCycleManagementProperties(serviceInstance, plan, user);

        // Save the updated service instance
        serviceInstanceRepository.save(serviceInstance);
        log.debug("Saved service instance ID: {}", serviceInstance.getId());

        // Provision quotas
        provisionQuotaOptimized(serviceInstance, bucketInstanceList, quotaDetails, bucketMap, qosProfileMap);

        log.debug("Completed processing service instance ID: {} in transaction", serviceInstance.getId());
    }

    private void updateCycleManagementProperties(ServiceInstance serviceInstance, Plan plan, UserEntity user){
        log.debug("Updating cycle management properties for User: {}, Billing: {}", user.getUserName(), user.getBilling());
        try {
            LocalDateTime serviceStartDate;
            LocalDateTime cycleStartDate;
            LocalDateTime cycleEndDate;
            LocalDateTime nextCycleDate;

            serviceStartDate = serviceInstance.getNextCycleStartDate();
            cycleStartDate = serviceStartDate;
            long validityDays = getNumberOfValidityDays(plan.getRecurringPeriod(), user.getBilling(), cycleStartDate);
            cycleEndDate = cycleStartDate.plusDays(validityDays - 1);
            nextCycleDate = cycleEndDate.plusDays(1);
            log.debug("Calculated validity days: {}, next Cycle date: {}", validityDays, nextCycleDate);

            // Check if nextCycleDate should be set to null
            if (nextCycleDate.isAfter(serviceInstance.getExpiryDate()) || Boolean.FALSE.equals(plan.getRecurringFlag())) {
                log.debug("Setting next cycle date to null (Next cycle after expiry or non-recurring plan)");
                nextCycleDate = null;
            }

            // Update properties
            serviceInstance.setServiceStartDate(serviceStartDate);
            serviceInstance.setServiceCycleStartDate(cycleStartDate);
            serviceInstance.setServiceCycleEndDate(cycleEndDate);
            serviceInstance.setNextCycleStartDate(nextCycleDate);

            log.debug("Cycle management properties set successfully - Start: {}, End: {}, Expiry: {}",
                    cycleStartDate, cycleEndDate, serviceInstance.getExpiryDate());
        } catch (Exception ex){
            log.error("Error updating cycle management properties for User: {}", user.getUserName(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Error setting cycle management properties: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private Integer getNumberOfValidityDays(String recurringPeriod, String billing, LocalDateTime currentBillCycleDate) {
        log.debug("Calculating validity days for recurring period: {}, billing: {}", recurringPeriod, billing);
        try {
            // Check if recurring period is Daily
            if ("DAILY".equalsIgnoreCase(recurringPeriod)) {
                log.debug("Validity days: 1 (Daily recurring)");
                return 1;
            }

            // Check if recurring period is Weekly
            if ("WEEKLY".equalsIgnoreCase(recurringPeriod)) {
                log.debug("Validity days: 7 (Weekly recurring)");
                return 7;
            }

            // Check if billing is Calendar Month or Daily
            if ("2".equals(billing) || "1".equals(billing)) {
                int days = currentBillCycleDate.toLocalDate().lengthOfMonth();
                log.debug("Validity days: {} (Calendar month length)", days);
                return days;
            }

            // Default case: return number of days till next bill cycle date
            LocalDateTime nextBillCycleDate = currentBillCycleDate.plusMonths(1);
            int days = (int) ChronoUnit.DAYS.between(
                    currentBillCycleDate.toLocalDate(),
                    nextBillCycleDate.toLocalDate()
            );
            log.debug("Validity days: {} (Days till next cycle)", days);
            return days;

        } catch (Exception ex) {
            log.error("Error calculating validity days for recurring period: {}, billing: {}", recurringPeriod, billing, ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Error calculating validity days: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }


    }



    private void provisionQuotaOptimized(ServiceInstance serviceInstance, List<BucketInstance> bucketInstanceList,
                                         List<PlanToBucket> quotaDetails, Map<String, Bucket> bucketMap,
                                         Map<Long, QOSProfile> qosProfileMap) {
        log.debug("Starting optimized quota provisioning for Service Instance ID: {}", serviceInstance.getId());

        try {
            if (bucketInstanceList == null || bucketInstanceList.isEmpty()) {
                log.error("No quota details found for Service ID: {}", serviceInstance.getId());
                throw new AAAException(LogMessages.ERROR_NOT_FOUND, "NO_QUOTA_DETAILS_FOUND_FOR_SERVICE", HttpStatus.NOT_FOUND);
            }

            if (quotaDetails == null || quotaDetails.isEmpty()) {
                log.error("No quota details found for Plan ID: {}", serviceInstance.getPlanId());
                throw new AAAException(LogMessages.ERROR_NOT_FOUND, "NO_QUOTA_DETAILS_FOUND", HttpStatus.NOT_FOUND);
            }
            log.debug("Found {} quota details for Plan ID: {}", quotaDetails.size(), serviceInstance.getPlanId());

            log.debug("Performing new quota provision for Service Instance ID: {}", serviceInstance.getId());
            newQuotaProvisionOptimized(quotaDetails, serviceInstance, bucketMap, qosProfileMap);

            log.debug("Performing carry forward provision for Service Instance ID: {}", serviceInstance.getId());
            createCarryForwardBucketsOptimized(bucketInstanceList, quotaDetails, serviceInstance, bucketMap, qosProfileMap);

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error provisioning quota for Service Instance ID: {}", serviceInstance.getId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    private void newQuotaProvisionOptimized(List<PlanToBucket> quotaDetails, ServiceInstance serviceInstance,
                                            Map<String, Bucket> bucketMap, Map<Long, QOSProfile> qosProfileMap) {
        log.debug("Starting optimized new quota provision for Service Instance ID: {}, Quota count: {}",
                serviceInstance.getId(), quotaDetails.size());

        try {
            List<BucketInstance> bucketInstanceList = new ArrayList<>();
            for (PlanToBucket planToBucket : quotaDetails) {
                BucketInstance bucketInstance = new BucketInstance();
                setBucketDetailsOptimized(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket,
                        Boolean.FALSE, null, bucketMap, qosProfileMap);
                bucketInstanceList.add(bucketInstance);
            }
            bucketInstanceRepository.saveAll(bucketInstanceList);
            log.info("Saved {} bucket instances for Service Instance ID: {}",
                    bucketInstanceList.size(), serviceInstance.getId());

            // Update user cache with newly created bucket instances
            updateUserCacheWithBuckets(serviceInstance.getUsername(), bucketInstanceList, serviceInstance);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during optimized quota provision for Service Instance ID: {}",
                    serviceInstance.getId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    @SuppressWarnings("java:S107")
    private void setBucketDetailsOptimized(String bucketId, BucketInstance bucketInstance, ServiceInstance serviceInstance,
                                           PlanToBucket planToBucket, boolean isCFBucket, Long currentBalance,
                                           Map<String, Bucket> bucketMap, Map<Long, QOSProfile> qosProfileMap) {
        try {
            Bucket bucket = bucketMap.get(bucketId);
            if (bucket == null) {
                log.error("Bucket not found: {}", bucketId);
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "BUCKET_NOT_FOUND " + bucketId, HttpStatus.NOT_FOUND);
            }

            QOSProfile qosProfile = qosProfileMap.get(bucket.getQosId());
            if (qosProfile == null) {
                log.error("QoS profile not found: {}", bucket.getQosId());
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "QOS_PROFILE_NOT_FOUND " + bucket.getQosId(), HttpStatus.NOT_FOUND);
            }

            bucketInstance.setBucketId(bucket.getBucketId());
            bucketInstance.setBucketType(bucket.getBucketType());
            bucketInstance.setPriority(bucket.getPriority());
            bucketInstance.setTimeWindow(bucket.getTimeWindow());
            bucketInstance.setRule(qosProfile.getBngCode());
            bucketInstance.setServiceId(serviceInstance.getId());
            bucketInstance.setCarryForward(planToBucket.getCarryForward());
            bucketInstance.setMaxCarryForward(planToBucket.getMaxCarryForward());
            bucketInstance.setTotalCarryForward(planToBucket.getTotalCarryForward());
            bucketInstance.setConsumptionLimit(planToBucket.getConsumptionLimit());
            bucketInstance.setConsumptionLimitWindow(planToBucket.getConsumptionLimitWindow());
            bucketInstance.setCurrentBalance(planToBucket.getInitialQuota());
            bucketInstance.setCarryForwardValidity(planToBucket.getCarryForwardValidity());
            bucketInstance.setInitialBalance(planToBucket.getInitialQuota());
            bucketInstance.setExpiration(serviceInstance.getExpiryDate());
            bucketInstance.setUsage(0L);

            if (isCFBucket && currentBalance != null) {
                bucketInstance.setBucketType(Constants.CARRY_FORWARD_BUCKET);
                bucketInstance.setCarryForward(Boolean.FALSE);
                if (currentBalance > planToBucket.getMaxCarryForward())
                    currentBalance = planToBucket.getMaxCarryForward();
                bucketInstance.setCurrentBalance(currentBalance);
                bucketInstance.setInitialBalance(currentBalance);
                bucketInstance.setExpiration(serviceInstance.getServiceStartDate()
                        .plusDays(planToBucket.getCarryForwardValidity()));
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error setting optimized bucket details for Bucket ID: {}", bucketId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * Builds a map of existing carry forward buckets grouped by bucket ID
     * @param currentBucketInstanceList List of current bucket instances
     * @param tomorrow Tomorrow's date for filtering
     * @return Map of bucket ID to list of carry forward bucket instances
     */
    private Map<String, List<BucketInstance>> buildExistingCFBucketsMap(
            List<BucketInstance> currentBucketInstanceList, LocalDate tomorrow) {
        Map<String, List<BucketInstance>> existingCFBucketsByIdMap = new HashMap<>();

        for (BucketInstance currentBucketInstance : currentBucketInstanceList) {
            if (isEligibleForCarryForward(currentBucketInstance, tomorrow)) {
                existingCFBucketsByIdMap
                        .computeIfAbsent(currentBucketInstance.getBucketId(), k -> new ArrayList<>())
                        .add(currentBucketInstance);
            }
        }

        // Sort each list by expiration
        existingCFBucketsByIdMap.values().forEach(list ->
            list.sort(Comparator.comparing(BucketInstance::getExpiration)));

        return existingCFBucketsByIdMap;
    }

    /**
     * Checks if a bucket instance is eligible for carry forward
     */
    private boolean isEligibleForCarryForward(BucketInstance bucketInstance, LocalDate tomorrow) {
        return bucketInstance.getBucketType().equals(Constants.CARRY_FORWARD_BUCKET)
                && !bucketInstance.getExpiration().toLocalDate().isEqual(tomorrow);
    }

    /**
     * Calculates total carry forward amount including existing CF buckets
     */
    private Long calculateTotalCFAmount(BucketInstance newBucket, List<BucketInstance> existingCFBuckets) {
        Long total = newBucket.getCurrentBalance();

        if (existingCFBuckets != null) {
            for (BucketInstance existingCFBucket : existingCFBuckets) {
                total += existingCFBucket.getCurrentBalance();
            }
        }

        return total;
    }

    /**
     * Adjusts existing carry forward buckets to respect total carry forward limit
     */
    private void adjustExistingCFBuckets(List<BucketInstance> existingCFBuckets,
                                        Long totalCFAmount,
                                        Long totalCFLimit,
                                        List<BucketInstance> updatesToSave) {
        Long remainingTotal = totalCFAmount;

        for (BucketInstance existingCFBucket : existingCFBuckets) {
            if (remainingTotal < totalCFLimit) {
                break;
            }

            Long excess = remainingTotal - totalCFLimit;
            if (excess < existingCFBucket.getCurrentBalance()) {
                existingCFBucket.setCurrentBalance(existingCFBucket.getCurrentBalance() - excess);
                updatesToSave.add(existingCFBucket);
                break;
            } else {
                remainingTotal -= existingCFBucket.getCurrentBalance();
                existingCFBucket.setCurrentBalance(0L);
                updatesToSave.add(existingCFBucket);
            }
        }
    }

    /**
     * Context object for carry forward bucket processing
     * Groups related parameters to reduce method parameter count
     */
    private static class CarryForwardContext {
        final ServiceInstance serviceInstance;
        final Map<String, Bucket> bucketMap;
        final Map<Long, QOSProfile> qosProfileMap;
        final Map<String, List<BucketInstance>> existingCFBucketsByIdMap;
        final List<BucketInstance> newCarryForwardBucketList;
        final List<BucketInstance> updatesToSave;

        CarryForwardContext(ServiceInstance serviceInstance,
                           Map<String, Bucket> bucketMap,
                           Map<Long, QOSProfile> qosProfileMap,
                           Map<String, List<BucketInstance>> existingCFBucketsByIdMap,
                           List<BucketInstance> newCarryForwardBucketList,
                           List<BucketInstance> updatesToSave) {
            this.serviceInstance = serviceInstance;
            this.bucketMap = bucketMap;
            this.qosProfileMap = qosProfileMap;
            this.existingCFBucketsByIdMap = existingCFBucketsByIdMap;
            this.newCarryForwardBucketList = newCarryForwardBucketList;
            this.updatesToSave = updatesToSave;
        }
    }

    /**
     * Processes a single carry forward bucket creation
     */
    private void processCarryForwardBucket(PlanToBucket planToBucket,
                                          BucketInstance carryForwardBucket,
                                          CarryForwardContext context) {
        BucketInstance bucketInstance = new BucketInstance();
        setBucketDetailsOptimized(planToBucket.getBucketId(), bucketInstance, context.serviceInstance, planToBucket,
                Boolean.TRUE, carryForwardBucket.getCurrentBalance(), context.bucketMap, context.qosProfileMap);

        List<BucketInstance> existingCFBuckets = context.existingCFBucketsByIdMap.get(planToBucket.getBucketId());
        Long totalCFAmount = calculateTotalCFAmount(bucketInstance, existingCFBuckets);

        if (existingCFBuckets != null) {
            adjustExistingCFBuckets(existingCFBuckets, totalCFAmount,
                bucketInstance.getTotalCarryForward(), context.updatesToSave);
        }

        context.newCarryForwardBucketList.add(bucketInstance);
    }

    /**
     * Checks if carry forward bucket has a valid balance
     */
    private boolean hasValidBalance(BucketInstance bucket) {
        return bucket != null
            && bucket.getCurrentBalance() != null
            && bucket.getCurrentBalance() != 0L;
    }

    private void createCarryForwardBucketsOptimized(List<BucketInstance> currentBucketInstanceList,
                                                    List<PlanToBucket> quotaDetails,
                                                    ServiceInstance serviceInstance,
                                                    Map<String, Bucket> bucketMap,
                                                    Map<Long, QOSProfile> qosProfileMap) {
        Long serviceId = serviceInstance.getId();
        log.debug("Starting optimized create carry forward buckets for Service Instance ID: {}, Quota count: {}",
                serviceId, quotaDetails.size());

        try {
            List<BucketInstance> newCarryForwardBucketList = new ArrayList<>();
            List<BucketInstance> updatesToSave = new ArrayList<>();
            LocalDate tomorrow = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).plusDays(1);

            Map<String, List<BucketInstance>> existingCFBucketsByIdMap =
                buildExistingCFBucketsMap(currentBucketInstanceList, tomorrow);

            CarryForwardContext context = new CarryForwardContext(
                serviceInstance, bucketMap, qosProfileMap, existingCFBucketsByIdMap,
                newCarryForwardBucketList, updatesToSave);

            Map<String, BucketInstance> currentBucketMap = currentBucketInstanceList.stream()
                    .collect(Collectors.toMap(BucketInstance::getBucketId, b -> b, (b1, b2) -> b1));

            for (PlanToBucket planToBucket : quotaDetails) {
                if (Boolean.TRUE.equals(planToBucket.getCarryForward())) {
                    BucketInstance carryForwardBucket = currentBucketMap.get(planToBucket.getBucketId());

                    if (hasValidBalance(carryForwardBucket)) {
                        processCarryForwardBucket(planToBucket, carryForwardBucket, context);
                    } else if (carryForwardBucket == null) {
                        log.error("Bucket Id: {} Bucket is not in current bucket list. Service Id: {}",
                                planToBucket.getBucketId(), serviceId);
                    }
                }
            }

            // BATCH SAVE: Save all updates and new buckets together
            bucketInstanceRepository.saveAll(updatesToSave);
            bucketInstanceRepository.saveAll(newCarryForwardBucketList);
            log.info("Saved {} carryforward bucket instances for Service Instance ID: {}",
                    newCarryForwardBucketList.size(), serviceId);

            // Update user cache with newly created carry-forward bucket instances
            if (!newCarryForwardBucketList.isEmpty()) {
                updateUserCacheWithBuckets(serviceInstance.getUsername(), newCarryForwardBucketList, serviceInstance);
            }

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during optimized create carry forward buckets for Service Instance ID: {}", serviceId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * Updates user cache with newly created bucket instances
     * Converts BucketInstances to Balance objects and updates Redis cache via UserCacheService
     *
     * @param username Username to update cache for
     * @param newBucketInstances List of newly created bucket instances to add to cache
     * @param serviceInstance ServiceInstance for getting service details
     */
    private void updateUserCacheWithBuckets(String username, List<BucketInstance> newBucketInstances,
                                            ServiceInstance serviceInstance) {
        log.debug("Updating user cache with {} new bucket instances for username: {}",
                newBucketInstances.size(), username);

        try {
            // Get user entity to retrieve userId
            if (username == null) {
                log.error("User not found for username: {}", username);
                throw new AAAException(LogMessages.ERROR_NOT_FOUND,
                        "USER_NOT_FOUND: " + username, HttpStatus.NOT_FOUND);
            }

            // Get existing user session data from Redis cache
            UserSessionData userSessionData = userCacheService.getUserData(username);

            if (userSessionData == null) {
                log.warn("No user session data found in cache for username: {}. Skipping cache update.",
                        username);
                return;
            }

            // Convert BucketInstances to Balance objects
            List<Balance> newBalances = convertBucketInstancesToBalances(newBucketInstances, serviceInstance);

            // Add new balances to existing balance list
            if (userSessionData.getBalance() == null) {
                userSessionData.setBalance(new ArrayList<>());
            }
            userSessionData.getBalance().addAll(newBalances);

            log.debug("Added {} balance entries to user session data for username: {}",
                    newBalances.size(), username);

            // Update Redis cache with updated user session data
            userCacheService.updateUserAndRelatedCaches(username, userSessionData, username);

            log.info("Successfully updated cache for username: {} with {} new bucket instances",
                    username, newBucketInstances.size());

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error updating user cache for username: {}", username, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to update user cache: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Converts BucketInstance entities to Balance DTOs
     *
     * @param bucketInstances List of BucketInstance entities
     * @param serviceInstance ServiceInstance for getting service details
     * @return List of Balance DTOs
     */
    private List<Balance> convertBucketInstancesToBalances(List<BucketInstance> bucketInstances,
                                                           ServiceInstance serviceInstance) {
        List<Balance> balances = new ArrayList<>();

        for (BucketInstance bucketInstance : bucketInstances) {
            Balance balance = new Balance();

            // Set balance properties from bucket instance
            balance.setInitialBalance(bucketInstance.getInitialBalance());
            balance.setQuota(bucketInstance.getCurrentBalance());
            balance.setServiceExpiry(serviceInstance.getExpiryDate());
            balance.setBucketExpiryDate(bucketInstance.getExpiration());
            balance.setBucketId(String.valueOf(bucketInstance.getId()));
            balance.setServiceId(bucketInstance.getServiceId() != null ?
                    bucketInstance.getServiceId().toString() : null);
            balance.setPriority(bucketInstance.getPriority());
            balance.setServiceStartDate(serviceInstance.getServiceStartDate());
            balance.setServiceStatus(serviceInstance.getStatus());
            balance.setTimeWindow(bucketInstance.getTimeWindow());
            balance.setConsumptionLimit(bucketInstance.getConsumptionLimit());
            balance.setConsumptionLimitWindow(Long.valueOf(bucketInstance.getConsumptionLimitWindow()));
            balance.setBucketUsername(serviceInstance.getUsername());
            balance.setUnlimited(false); // Default to false, update if needed based on business logic
            balance.setGroup(Boolean.TRUE.equals(serviceInstance.getIsGroup()));
            balance.setUsage(bucketInstance.getUsage() != null ? bucketInstance.getUsage() : 0L);

            balances.add(balance);

            log.trace("Converted BucketInstance to Balance - BucketId: {}, Quota: {}, Priority: {}",
                    bucketInstance.getBucketId(), bucketInstance.getCurrentBalance(), bucketInstance.getPriority());
        }

        return balances;
    }

    /**
     * Saves service processing failure details to the database.
     * This method persists failure information for monitoring, analysis, and potential retry attempts.
     *
     * @param serviceInstance The service instance that failed
     * @param plan The plan associated with the service (can be null if plan not found)
     * @param username The username associated with the failed service
     * @param exception The exception that caused the failure
     * @param batchId The batch ID of the processing run
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveServiceProcessingFailure(ServiceInstance serviceInstance, Plan plan,
                                             String username, Exception exception, String batchId) {
        try {
            // Extract stack trace
            String stackTrace = getStackTrace(exception);
            String errorMessage = exception.getMessage();
            String errorType = exception.getClass().getSimpleName();

            // Build additional info
            String additionalInfo = String.format("ServiceId: %d, NextCycleStart: %s",
                    serviceInstance.getId(),
                    serviceInstance.getNextCycleStartDate());

            // Create failure record
            ServiceProcessingFailure failure = ServiceProcessingFailure.builder()
                    .serviceInstanceId(serviceInstance.getId())
                    .username(username)
                    .planId(serviceInstance.getPlanId())
                    .planName(plan != null ? plan.getPlanName() : serviceInstance.getPlanName())
                    .errorType(errorType)
                    .errorMessage(truncateString(errorMessage, 4000))
                    .stackTrace(truncateString(stackTrace, 4000))
                    .retryCount(0)
                    .processingStatus("FAILED")
                    .batchId(batchId)
                    .additionalInfo(truncateString(additionalInfo, 1000))
                    .build();

            serviceProcessingFailureRepository.save(failure);
            log.debug("Saved failure record for service ID: {}, username: {}", serviceInstance.getId(), username);

        } catch (Exception ex) {
            // Log but don't throw - we don't want failure tracking to break the main processing
            log.error("Failed to save processing failure record for service ID: {}. Error: {}",
                    serviceInstance.getId(), ex.getMessage(), ex);
        }
    }

    /**
     * Extracts stack trace from exception as a string
     */
    private String getStackTrace(Exception exception) {
        if (exception == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Truncates a string to specified max length
     */
    private String truncateString(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

}
