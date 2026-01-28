package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteBucketInstanceService {

    private final BucketInstanceRepository bucketInstanceRepository;

    @Value("${delete-expired-buckets.chunk-size}")
    private int chunkSize;

    public void deleteExpiredBucketInstance(){
        log.debug("Starting delete expired buckets");

        LocalDateTime today = LocalDate.now(ZoneId.of(Constants.SL_TIME_ZONE)).atStartOfDay();
        int pageNumber = 0;
        Page<BucketInstance> userPage;

        do {
            Pageable pageable = PageRequest.of(pageNumber, chunkSize);

            userPage = bucketInstanceRepository.findExpiredBuckets(today, pageable);
            List<BucketInstance> expiringTomorrowList = userPage.getContent();

            bucketInstanceRepository.deleteAll(expiringTomorrowList);
            log.info("Deleted {}  expired buckets", expiringTomorrowList.size());
            pageNumber ++;

        } while (userPage.hasNext());

        log.info("Finished deleting expired buckets");

    }
}
