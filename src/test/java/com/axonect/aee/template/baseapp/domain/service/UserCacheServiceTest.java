package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.axonect.aee.template.baseapp.domain.exception.CacheOperationException;
import com.axonect.aee.template.baseapp.domain.exception.CacheSerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserCacheServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplateString;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisTemplate<String, byte[]> redisTemplateBytes;

    @Mock
    private ValueOperations<String, byte[]> byteValueOperations;

    @Mock
    private SessionCacheCodec sessionCacheCodec;

    @InjectMocks
    private UserCacheService userCacheService;

    private final String userId = "123";
    private final String userName = "testUser";
    private final String userKey = "user:123";

    // CBOR map header byte (0xBF) - not a JSON '{', mirrors a real binary payload
    private final byte[] encoded = new byte[]{(byte) 0xBF, 0x01, 0x02};

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplateString.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplateBytes.opsForValue()).thenReturn(byteValueOperations);
    }

    // --- getUserData Tests ---

    @Test
    void getUserData_Success() throws Exception {
        UserSessionData expectedData = new UserSessionData();

        when(byteValueOperations.get(userKey)).thenReturn(encoded);
        when(sessionCacheCodec.decode(encoded)).thenReturn(expectedData);

        UserSessionData result = userCacheService.getUserData(userId);

        assertThat(result).isNotNull();
        verify(byteValueOperations).get(userKey);
    }

    @Test
    void getUserData_NotFound_ReturnsNull() {
        when(byteValueOperations.get(userKey)).thenReturn(null);

        UserSessionData result = userCacheService.getUserData(userId);

        assertThat(result).isNull();
    }

    @Test
    void getUserData_DeserializationError_ThrowsException() throws Exception {
        when(byteValueOperations.get(userKey)).thenReturn(encoded);
        when(sessionCacheCodec.decode(any()))
                .thenThrow(new RuntimeException("CBOR error"));

        assertThatThrownBy(() -> userCacheService.getUserData(userId))
                .isInstanceOf(CacheSerializationException.class);
    }

    // --- updateUserAndRelatedCaches Tests ---

    @Test
    @SuppressWarnings("java:S6068")
    void updateUserAndRelatedCaches_OnlyUserCache_Success() throws Exception {
        UserSessionData data = new UserSessionData();
        data.setGroupId("1"); // Should trigger only user cache update

        when(sessionCacheCodec.encode(data)).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, data, userName);

        verify(byteValueOperations).set(userKey, encoded);
        verify(valueOperations, never()).set(contains("group:"), anyString());
    }

    @Test
    @SuppressWarnings("java:S6068")
    void updateUserAndRelatedCaches_WithGroupCache_Success() throws Exception {
        UserSessionData data = new UserSessionData();
        data.setGroupId("99"); // Not "1", triggers group update
        data.setConcurrency(5);
        data.setUserStatus("ACTIVE");
        data.setSessionTimeOut("3600");

        when(sessionCacheCodec.encode(data)).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, data, userName);

        verify(byteValueOperations).set(userKey, encoded);
        verify(valueOperations).set(eq("group:" + userName), contains("99,5,ACTIVE,3600"));
    }


    // --- Balance Cleanup Tests (removeExpiredBalanceElements) ---

    @Test
    void removeExpiredBalanceElements_RemovesOldBuckets() throws Exception {
        UserSessionData data = new UserSessionData();
        List<Balance> balances = new ArrayList<>();

        // Valid bucket
        Balance valid = new Balance();
        valid.setBucketExpiryDate(LocalDateTime.now().plusDays(1));

        // Expired bucket (more than 1 day ago)
        Balance expired = new Balance();
        expired.setBucketExpiryDate(LocalDateTime.now().minusDays(2));
        expired.setBucketId("EXP1");

        balances.add(valid);
        balances.add(expired);
        data.setBalance(balances);
        data.setGroupId("1");

        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, data, userName);

        assertThat(data.getBalance()).hasSize(1);
        assertThat(data.getBalance().get(0).getBucketExpiryDate()).isAfter(LocalDateTime.now());
    }

    // --- Other CRUD Operations ---

    @Test
    void getGroupData_Success() {
        when(valueOperations.get("group:" + userName)).thenReturn("data");
        String result = userCacheService.getGroupData(userName);
        assertThat(result).isEqualTo("data");
    }

    @Test
    void getGroupData_Exception_ThrowsCustom() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException());
        assertThatThrownBy(() -> userCacheService.getGroupData(userName))
                .isInstanceOf(CacheOperationException.class);
    }


    @Test
    void deleteGroupData_Success() {
        userCacheService.deleteGroupData(userName);
        verify(redisTemplateString).delete("group:" + userName);
    }

    @Test
    void userDataExists_ReturnsTrue() {
        when(redisTemplateString.hasKey(userKey)).thenReturn(true);
        boolean exists = userCacheService.userDataExists(userId);
        assertThat(exists).isTrue();
    }

    @Test
    void userDataExists_Exception_ReturnsFalse() {
        when(redisTemplateString.hasKey(anyString())).thenThrow(new RuntimeException());
        boolean exists = userCacheService.userDataExists(userId);
        assertThat(exists).isFalse();
    }


    @Test
    void getGroupData_Exception() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException());
        assertThrows(CacheOperationException.class, () -> userCacheService.getGroupData(userName));
    }


    @Test
    void deleteUserData_Exception() {
        doThrow(new RuntimeException()).when(redisTemplateString).delete(anyString());
        assertThrows(CacheOperationException.class, () -> userCacheService.deleteUserData(userId));
    }

    @Test
    void userDataExists_Success() {
        when(redisTemplateString.hasKey("user:123")).thenReturn(true);
        assertTrue(userCacheService.userDataExists(userId));
    }

    @Test
    void userDataExists_ExceptionReturnsFalse() {
        when(redisTemplateString.hasKey(anyString())).thenThrow(new RuntimeException());
        assertFalse(userCacheService.userDataExists(userId));
    }

    @Test
    void shutdown_CoversAllLines() {
        assertDoesNotThrow(() -> userCacheService.shutdown());
    }



    @Test
    void getUserData_ReturnNullWhenPayloadMissing() {
        when(byteValueOperations.get(anyString())).thenReturn(null);
        assertNull(userCacheService.getUserData(userId));
    }

    @Test
    void getUserData_ThrowsSerializationException() throws Exception {
        when(byteValueOperations.get(anyString())).thenReturn(encoded);
        when(sessionCacheCodec.decode(any()))
                .thenThrow(new RuntimeException("CBOR error"));

        assertThrows(CacheSerializationException.class, () -> userCacheService.getUserData(userId));
    }


    // --- UPDATE CACHE PATHS ---

    @Test
    void updateUserAndRelatedCaches_FullUpdate() throws Exception {
        UserSessionData testData = new UserSessionData();
        testData.setGroupId("non-one"); // Triggers shouldUpdateGroupCache -> true
        testData.setConcurrency(5);
        testData.setUserStatus("active");
        testData.setSessionTimeOut("3600");

        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, testData, userName);

        verify(byteValueOperations, times(1)).set(eq("user:123"), any());
        verify(valueOperations, times(1)).set(startsWith("group:"), anyString());
    }

    @Test
    void updateUserAndRelatedCaches_UserOnly_WhenGroupIdIsOne() throws Exception {
        UserSessionData testData = new UserSessionData();
        testData.setGroupId("1"); // Triggers shouldUpdateGroupCache -> false
        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, testData, userName);

        verify(byteValueOperations, times(1)).set(eq("user:123"), any());
        verify(valueOperations, never()).set(startsWith("group:"), anyString());
    }

    // --- BALANCE CLEANUP LOGIC ---

    @Test
    void removeExpiredBalanceElements_LogicCoverage() throws Exception {
        UserSessionData testData = new UserSessionData();
        List<Balance> balances = new ArrayList<>();

        // Expired (3 days ago)
        Balance b1 = new Balance();
        b1.setBucketExpiryDate(LocalDateTime.now().minusDays(3));
        b1.setBucketId("B1");

        // Valid (Future)
        Balance b2 = new Balance();
        b2.setBucketExpiryDate(LocalDateTime.now().plusDays(1));
        b2.setBucketId("B2");

        balances.add(b1);
        balances.add(b2);
        testData.setBalance(balances);
        testData.setGroupId("1");

        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, testData, userName);

        // Verification: The list should have been filtered to only contain b2
        assertEquals(1, testData.getBalance().size());
        assertEquals("B2", testData.getBalance().get(0).getBucketId());
    }


    @Test
    void updateUserAndRelatedCaches_GroupUpdate_True() throws Exception {
        UserSessionData testData = new UserSessionData();
        testData.setGroupId("99");
        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, testData, userName);

        verify(byteValueOperations).set(eq("user:123"), any());
        verify(valueOperations).set(startsWith("group:"), anyString());
    }

    @Test
    void updateUserAndRelatedCaches_GroupUpdate_False_WhenGroupIdIsOne() throws Exception {
        UserSessionData testData = new UserSessionData();
        testData.setGroupId("1");
        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, testData, userName);

        verify(byteValueOperations).set(eq("user:123"), any());
        verify(valueOperations, never()).set(startsWith("group:"), anyString());
    }

    @Test
    void removeExpiredBalanceElements_Coverage() throws Exception {
        UserSessionData testData = new UserSessionData();
        List<Balance> balances = new ArrayList<>();

        // This bucket is expired (2 days ago)
        Balance expired = new Balance();
        expired.setBucketExpiryDate(LocalDateTime.now().minusDays(2));
        expired.setBucketId("EXP");

        // This bucket is valid (2 days in future)
        Balance valid = new Balance();
        valid.setBucketExpiryDate(LocalDateTime.now().plusDays(2));
        valid.setBucketId("VAL");

        balances.add(expired);
        balances.add(valid);
        testData.setBalance(balances);
        testData.setGroupId("1"); // Simplify update path

        when(sessionCacheCodec.encode(any())).thenReturn(encoded);

        userCacheService.updateUserAndRelatedCaches(userId, testData, userName);

        // Assert stream filtered correctly
        assertEquals(1, testData.getBalance().size());
        assertEquals("VAL", testData.getBalance().get(0).getBucketId());
    }



    @Test
    void deleteUserData_Success() {
        userCacheService.deleteUserData(userId);
        verify(redisTemplateString).delete("user:123");
    }
}
