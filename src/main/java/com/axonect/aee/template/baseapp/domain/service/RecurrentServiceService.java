package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.BucketRepository;
import com.axonect.aee.template.baseapp.application.repository.PlanRepository;
import com.axonect.aee.template.baseapp.application.repository.PlanToBucketRepository;
import com.axonect.aee.template.baseapp.application.repository.QOSProfileRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.UserRepository;
import com.axonect.aee.template.baseapp.domain.entities.repo.Bucket;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.Plan;
import com.axonect.aee.template.baseapp.domain.entities.repo.PlanToBucket;
import com.axonect.aee.template.baseapp.domain.entities.repo.QOSProfile;
import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.entities.repo.UserEntity;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
    private final AccountingCacheManagementService accountingCacheManagementService;

    @Value("${recurrent-service.chunk-size}")
    private int chunkSize;

    /**
     * Batch process to reactivate recurring services that are due for the next cycle.
     * Filters services based on:
     * - NEXT_CYCLE_START_DATE = Tomorrow
     * - RECURRING_FLAG = true
     * - EXPIRY_DATE is in the future (not expired)
     * Processes services in chunks for better performance.
     */
    public void reactivateExpiredRecurrentServices() {
        log.info("Reactivate expired recurrent services started..");

        int pageNumber = 0;
        Pageable pageable = PageRequest.of(pageNumber, chunkSize);
        Page<ServiceInstance> servicePage;

        LocalDateTime tomorrowStart = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE))
                .plusDays(1)
                .atStartOfDay(); // todo my SERVICE_INSTANCE table NEXT_CYCLE_START_DATE = '2026-01-31 11:16:28.966' store in this format need not retrive data from this value


        do {
            // Get services directly with filters: RECURRING_FLAG = 1, NEXT_CYCLE_START_DATE = Tomorrow, not expired
            servicePage = serviceInstanceRepository.findByRecurringFlagTrueAndNextCycleStartDateAndExpiryDateAfter(
                    tomorrowStart,
                    tomorrowStart,
                    pageable
            );

            List<ServiceInstance> services = servicePage.getContent();
            log.info("Processing {} services in batch (page {})", services.size(), pageNumber);

            for (ServiceInstance serviceInstance : services) {
                // Get username from service to fetch user details
                UserEntity user = userRepository.findAllByUserName(serviceInstance.getUsername());
                if (user == null) {
                    log.warn("User not found for service ID: {}, username: {}",
                            serviceInstance.getId(), serviceInstance.getUsername());
                    continue;
                }

                log.info("Updating service {} for user {}", serviceInstance.getPlanId(), user.getUserName());

                String planId = serviceInstance.getPlanId();

                Plan plan = planRepository.findByPlanId(planId).
                        orElseThrow(() -> {
                            log.error("Plan not found: {}", planId);
                            return new AAAException(
                                    LogMessages.ERROR_NOT_FOUND, LogMessages.PLAN_DOES_NOT_EXIST, HttpStatus.NOT_FOUND
                            );
                        });
                log.debug("Plan found: {} ({}), Recurring: {}", plan.getPlanName(), plan.getPlanType(), plan.getRecurringFlag());

                updateCycleManagementProperties(serviceInstance, plan, user);
                log.debug("Cycle management properties set - Cycle Start: {}, Cycle End: {}, Next Cycle: {}",
                        serviceInstance.getServiceCycleStartDate(),
                        serviceInstance.getServiceCycleEndDate(),
                        serviceInstance.getNextCycleStartDate());

                serviceInstance = serviceInstanceRepository.save(serviceInstance);
                log.info("Service instance updated with ID: {} for User: {}", serviceInstance.getId(), user.getUserName());

                provisionQuota(serviceInstance, plan.getPlanId());
            }

            pageNumber++;
            pageable = PageRequest.of(pageNumber, chunkSize);

        } while (!servicePage.isLast());
        log.info("Reactivate expired recurrent services Completed..");
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

    private LocalDateTime getCycleStartDate(LocalDateTime serviceStartDate, Integer cycleDate) {
        log.debug("Calculating cycle start date for service start: {}, cycle date: {}", serviceStartDate, cycleDate);

        // Get the cycle date of the current month at midnight
        LocalDateTime currentMonthCycleDate = serviceStartDate.withDayOfMonth(cycleDate)
                .toLocalDate().atStartOfDay();

        // If service start date is before the cycle date of current month, use previous month
        if (serviceStartDate.toLocalDate().isBefore(currentMonthCycleDate.toLocalDate())) {
            LocalDateTime previousMonth = currentMonthCycleDate.minusMonths(1);
            log.debug("Service starts before cycle date, cycle start date is in previous month: {}", previousMonth);
            return previousMonth;
        } else {
            // Service starts on or after cycle date, use current month
            log.debug("Cycle start date is in current month: {}", currentMonthCycleDate);
            return currentMonthCycleDate;
        }
    }

    private void provisionQuota(ServiceInstance serviceInstance, String planId) {
        log.debug("Starting quota provisioning for Service Instance ID: {}, Plan: {}",
                serviceInstance.getId(), planId);

        try {
            Long serviceId = serviceInstance.getId();

            List<BucketInstance> bucketInstanceList = bucketInstanceRepository.findByServiceId(serviceId);
            if (bucketInstanceList == null || bucketInstanceList.isEmpty()){
                log.error("No quota details found for Service ID: {}", serviceId);
                throw new AAAException(LogMessages.ERROR_NOT_FOUND,"NO_QUOTA_DETAILS_FOUND_FOR_SERVICE",HttpStatus.NOT_FOUND);
            }

            List<PlanToBucket> quotaDetails = planToBucketRepository.findByPlanId(planId);
            if (quotaDetails == null || quotaDetails.isEmpty()) {
                log.error("No quota details found for Plan ID: {}", planId);
                throw new AAAException(LogMessages.ERROR_NOT_FOUND,"NO_QUOTA_DETAILS_FOUND",HttpStatus.NOT_FOUND);
            }
            log.debug("Found {} quota details for Plan ID: {}", quotaDetails.size(), planId);


            log.debug("Performing new quota provision for Service Instance ID: {}", serviceInstance.getId());
            newQuotaProvision(quotaDetails, serviceInstance);

            log.debug("Performing carry forward provision for Service Instance ID: {}", serviceInstance.getId());
            createCarryForwardBuckets(bucketInstanceList, quotaDetails, serviceInstance);

//            log.debug("Performing delete expired quota for Service Instance ID: {}", serviceInstance.getId());
//            deleteExpiredBucketInstance(bucketInstanceList, serviceId);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error provisioning quota for Service Instance ID: {}, Plan: {}",
                    serviceInstance.getId(), planId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

    }

    private void newQuotaProvision(List<PlanToBucket> quotaDetails, ServiceInstance serviceInstance) {
        log.debug("Starting new quota provision for Service Instance ID: {}, Quota count: {}",
                serviceInstance.getId(), quotaDetails.size());

        try {
            List<BucketInstance> bucketInstanceList = new ArrayList<>();
            for (PlanToBucket planToBucket : quotaDetails) {
                BucketInstance bucketInstance = new BucketInstance();
                setBucketDetails(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket,
                        Boolean.FALSE, null);
                log.debug("Bucket provisioned - Bucket ID: {}, Initial quota: {}",
                        planToBucket.getBucketId(), planToBucket.getInitialQuota());
                bucketInstanceList.add(bucketInstance);
            }
            bucketInstanceRepository.saveAll(bucketInstanceList);
            log.info("Saved {} bucket instances for Service Instance ID: {}",
                    bucketInstanceList.size(), serviceInstance.getId());
            accountingCacheManagementService.syncBuckets(serviceInstance.getUsername(),serviceInstance.getStatus()
                    ,bucketInstanceList);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during direct quota provision for Service Instance ID: {}",
                    serviceInstance.getId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void setBucketDetails(String bucketId, BucketInstance bucketInstance, ServiceInstance serviceInstance,
                                  PlanToBucket planToBucket, boolean isCFBucket, Long currentBalance) {
        log.debug("Setting bucket details for Bucket ID: {}, Service Instance ID: {}", bucketId, serviceInstance.getId());
        try {
            Bucket bucket = bucketRepository.findByBucketId(bucketId)
                    .orElseThrow(() -> {
                        log.error("Bucket not found: {}", bucketId);
                        return new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "BUCKET_NOT_FOUND " + bucketId
                                , HttpStatus.NOT_FOUND);
                    });

            log.debug("Bucket found - Type: {}, Priority: {}, QoS ID: {}", bucket.getBucketType(), bucket.getPriority()
                    , bucket.getQosId());

            bucketInstance.setBucketId(bucket.getBucketId());
            bucketInstance.setBucketType(bucket.getBucketType());
            bucketInstance.setPriority(bucket.getPriority());
            bucketInstance.setTimeWindow(bucket.getTimeWindow());
            bucketInstance.setRule(getBNGCodeByRuleId(bucket.getQosId()));
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

            if (isCFBucket && currentBalance != null){
                bucketInstance.setBucketType(Constants.CARRY_FORWARD_BUCKET);
                bucketInstance.setCarryForward(Boolean.FALSE);
                if (currentBalance > planToBucket.getMaxCarryForward())
                    currentBalance = planToBucket.getMaxCarryForward();
                bucketInstance.setCurrentBalance(currentBalance);
                bucketInstance.setInitialBalance(currentBalance);
                bucketInstance.setExpiration(serviceInstance.getServiceStartDate()
                        .plusDays(planToBucket.getCarryForwardValidity()));
            }

            log.debug("Bucket details set successfully for Bucket ID: {}, Initial quota: {}",
                    bucketId, planToBucket.getInitialQuota());
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error setting bucket details for Bucket ID: {}", bucketId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String getBNGCodeByRuleId(Long qosId) {
        log.debug("Fetching BNG code for QoS ID: {}", qosId);
        try {
            QOSProfile qosProfile = qosProfileRepository.findById(qosId)
                    .orElseThrow(() -> {
                        log.error("QoS profile not found: {}", qosId);
                        return new AAAException(
                                LogMessages.ERROR_POLICY_CONFLICT,
                                "QOS_PROFILE_NOT_FOUND " + qosId,
                                HttpStatus.NOT_FOUND
                        );
                    });
            log.debug("BNG code retrieved: {} for QoS ID: {}", qosProfile.getBngCode(), qosId);
            return qosProfile.getBngCode();
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error fetching BNG code for QoS ID: {}", qosId, ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    ex.getMessage(),
                   HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }
    private void createCarryForwardBuckets(List<BucketInstance> currentBucketInstanceList, List<PlanToBucket> quotaDetails
            , ServiceInstance serviceInstance){

        Long serviceId = serviceInstance.getId();
        log.debug("Starting create carry forward buckets for Service Instance ID: {}, Quota count: {}",
                serviceId, quotaDetails.size());

        try {
            List<BucketInstance> newCarryForwardBucketList = new ArrayList<>();
            List<BucketInstance> existingCFBucketList = new ArrayList<>();

            // filter the carry forward bucket from existing bucket list (to validate Total CF limit)
            for (BucketInstance currentBucketInstance : currentBucketInstanceList) {
                LocalDate tomorrow  = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).plusDays(1);
                if (currentBucketInstance.getBucketType().equals(Constants.CARRY_FORWARD_BUCKET)
                        && !currentBucketInstance.getExpiration().toLocalDate().isEqual(tomorrow)){
                    existingCFBucketList.add(currentBucketInstance);
                }
            }
            existingCFBucketList.sort(Comparator.comparing(BucketInstance::getExpiration));

            for (PlanToBucket planToBucket : quotaDetails) {
                List<BucketInstance> currentCFBucketsForIdList = new ArrayList<>();
                Long totalCurrentCFAmount = 0L;

                if (Boolean.TRUE.equals(planToBucket.getCarryForward())){
                    BucketInstance carryForwardBucket = currentBucketInstanceList.stream()
                            .filter(b -> planToBucket.getBucketId().equals(b.getBucketId()))
                            .findFirst().orElse(null);
                    if (carryForwardBucket != null && carryForwardBucket.getCurrentBalance() != null
                            && carryForwardBucket.getCurrentBalance() != 0L) {
                        BucketInstance bucketInstance = new BucketInstance();
                        setBucketDetails(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket,
                                Boolean.TRUE, carryForwardBucket.getCurrentBalance());

                        for (BucketInstance existingCFBucket : existingCFBucketList){
                            if (existingCFBucket.getBucketId().equals(planToBucket.getBucketId())){
                                currentCFBucketsForIdList.add(existingCFBucket);
                                totalCurrentCFAmount += existingCFBucket.getCurrentBalance();
                            }
                        }
                        totalCurrentCFAmount += bucketInstance.getCurrentBalance();

                        for (BucketInstance currentCFBucketListForId : currentCFBucketsForIdList) {
                            if (totalCurrentCFAmount < bucketInstance.getTotalCarryForward())
                                break;
                            Long a = totalCurrentCFAmount - bucketInstance.getTotalCarryForward();
                            if (a < currentCFBucketListForId.getCurrentBalance()) {
                                currentCFBucketListForId.setCurrentBalance(currentCFBucketListForId.getCurrentBalance() - a);
                                bucketInstanceRepository.save(currentCFBucketListForId);
                                break;
                            } else {
                                currentCFBucketListForId.setCurrentBalance(0L);
                                totalCurrentCFAmount -= currentCFBucketListForId.getCurrentBalance();
                            }
                            bucketInstanceRepository.save(currentCFBucketListForId);

                            accountingCacheManagementService.syncBuckets(serviceInstance.getUsername(),serviceInstance.getStatus()
                                    , Collections.singletonList(currentCFBucketListForId));
                        }

                        newCarryForwardBucketList.add(bucketInstance);

                        log.debug("Identified carry forward bucket- Bucket ID: {}, serviceId: {}",
                                planToBucket.getBucketId(), serviceId);
                    } else if (carryForwardBucket == null) {
                        log.error("Bucket Id: {} Bucket is not in current bucket list. Service Id: {}"
                                ,planToBucket.getBucketId(), serviceId);
                    }
                }
            }
            bucketInstanceRepository.saveAll(newCarryForwardBucketList);
            log.info("Saved {} carryforward bucket instances for Service Instance ID: {}",
                    newCarryForwardBucketList.size(), serviceId);

            accountingCacheManagementService.syncBuckets(serviceInstance.getUsername(),serviceInstance.getStatus()
                    ,newCarryForwardBucketList);

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during create carry forward buckets for Service Instance ID: {}",
                    serviceId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }


    }

    private void deleteExpiredBucketInstance(List<BucketInstance> bucketInstanceList, Long serviceId){
        log.debug("Starting delete expired buckets for Service Instance ID: {}", serviceId);

        LocalDate tomorrow  = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(1);

        List<BucketInstance> expiringTomorrowList = bucketInstanceList.stream()
                .filter(b -> b.getExpiration() != null &&
                        b.getExpiration().toLocalDate().isEqual(tomorrow ))
                .toList();
        bucketInstanceRepository.deleteAll(expiringTomorrowList);
        log.info("Deleted {}  expired buckets for Service Instance ID: {}", bucketInstanceList.size(), serviceId);
    }



}
