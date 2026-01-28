//package com.axonect.aee.template.baseapp.domain.service;
//
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainRequestEntity;
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainResponseEntity;
//import org.junit.jupiter.api.Test;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.mockito.Mockito.when;
//
//class SampleManageServiceTest {
//
//    @Mock
//    private SampleDomainResponseEntity sampleDomainResponseEntity;
//
//    @InjectMocks
//    private SampleManageService sampleManageService;
//
//    @Test
//    void testProcess() {
//        MockitoAnnotations.initMocks(this);
//        SampleDomainRequestEntity sampleDomainRequestEntity = new SampleDomainRequestEntity();
//
//        when(sampleDomainResponseEntity.getResCode()).thenReturn("200");
//        when(sampleDomainResponseEntity.getResDesc()).thenReturn("Operation Success");
//
//        SampleDomainResponseEntity result = sampleManageService.process(sampleDomainRequestEntity);
//
//        assertEquals("200", result.getResCode());
//        assertEquals("Operation Success", result.getResDesc());
//    }
//}
