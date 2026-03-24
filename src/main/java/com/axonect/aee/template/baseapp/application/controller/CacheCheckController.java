package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.service.UserCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
@Slf4j
public class CacheCheckController {

    private final UserCacheService userCacheService;

    @GetMapping("/check")
    public ResponseEntity<UserSessionData> checkCache(@RequestParam String userId) {
        log.info("Cache check requested for userId: {}", userId);

        UserSessionData userData = userCacheService.getUserData(userId);

        if (userData == null) {
            log.info("No cache entry found for userId: {}", userId);
            return ResponseEntity.notFound().build();
        }

        log.info("Cache entry found for userId: {}", userId);
        return ResponseEntity.ok(userData);
    }
}
