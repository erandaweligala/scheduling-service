package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteBucketInstanceServiceTest {

    @Mock
    private BucketInstanceRepository bucketInstanceRepository;

    @InjectMocks
    private DeleteBucketInstanceService deleteBucketInstanceService;

    private final int chunkSize = 10;

    @BeforeEach
    void setUp() {
        // Manually inject the @Value field since we aren't loading the full Spring Context
        ReflectionTestUtils.setField(deleteBucketInstanceService, "chunkSize", chunkSize);
    }
    @Test
    @DisplayName("Should successfully delete buckets when one page is returned")
    void deleteExpiredBucketInstance_SinglePage() {
        // Arrange
        BucketInstance instance = new BucketInstance();
        List<BucketInstance> instanceList = Collections.singletonList(instance);
        Page<BucketInstance> page = new PageImpl<>(instanceList, PageRequest.of(0, chunkSize), 1);

        when(bucketInstanceRepository.findExpiredBuckets(any(), any())).thenReturn(page);

        // Act
        deleteBucketInstanceService.deleteExpiredBucketInstance();

        // Assert
        verify(bucketInstanceRepository, times(1)).findExpiredBuckets(any(), any());
        verify(bucketInstanceRepository, times(1)).deleteAll(instanceList);
    }


    @Test
    @DisplayName("Should do nothing if no expired buckets are found")
    void deleteExpiredBucketInstance_Empty() {
        // Arrange
        Page<BucketInstance> emptyPage = new PageImpl<>(Collections.emptyList());
        when(bucketInstanceRepository.findExpiredBuckets(any(), any())).thenReturn(emptyPage);

        // Act
        deleteBucketInstanceService.deleteExpiredBucketInstance();

        // Assert
        verify(bucketInstanceRepository, times(1)).findExpiredBuckets(any(), any());
        verify(bucketInstanceRepository, times(1)).deleteAll(Collections.emptyList());
    }
}