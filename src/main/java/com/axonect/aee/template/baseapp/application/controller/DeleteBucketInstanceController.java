package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.domain.service.DeleteBucketInstanceService;
import com.axonect.aee.template.baseapp.domain.service.ExpiryNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class DeleteBucketInstanceController {

    private final DeleteBucketInstanceService deleteBucketInstanceService;

    private final ExpiryNotificationService expiryNotificationService;

    @GetMapping("/delete/expired")
    public void deleteExpiredBucketInstances(){
        log.info("Started delete expired bucket instances");

        deleteBucketInstanceService.deleteExpiredBucketInstance();

        log.info("Completed delete expired bucket instances");
    }

    @GetMapping("/notification")
    public void sendNotification(){
        log.info("Started notification");

        expiryNotificationService.processExpiryNotifications();

        log.info("Completed notifications");
    }
}
