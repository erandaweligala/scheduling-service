package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.service.UserCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheCheckControllerTest {

    @Mock
    private UserCacheService userCacheService;

    @InjectMocks
    private CacheCheckController cacheCheckController;

    @Test
    void checkCache_UserFound_Returns200WithData() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("testUser");
        when(userCacheService.getUserData("123")).thenReturn(userData);

        ResponseEntity<UserSessionData> response = cacheCheckController.checkCache("123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(userData);
        verify(userCacheService).getUserData("123");
    }

    @Test
    void checkCache_UserNotFound_Returns404() {
        when(userCacheService.getUserData("456")).thenReturn(null);

        ResponseEntity<UserSessionData> response = cacheCheckController.checkCache("456");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verify(userCacheService).getUserData("456");
    }
}
