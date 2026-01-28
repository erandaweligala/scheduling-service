//package com.axonect.aee.template.baseapp.application.controller;
//
//import com.axonect.aee.template.baseapp.application.transformer.ResponseEntityTransformer;
//import com.axonect.aee.template.baseapp.application.transport.request.entities.SampleRequestEntity;
//import com.axonect.aee.template.baseapp.application.transport.response.transformers.SampleResponseTransformer;
//import com.axonect.aee.template.baseapp.application.validator.RequestEntityValidator;
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainRequestEntity;
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainResponseEntity;
//import com.axonect.aee.template.baseapp.domain.service.SampleManageService;
//import jakarta.servlet.http.HttpServletRequest;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.MethodSource;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//import org.modelmapper.ModelMapper;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.validation.BindingResult;
//
//import java.util.Arrays;
//import java.util.HashMap;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//class IntegratorControllerTest {
//
//    @Mock
//    private SampleManageService sampleManageService;
//
//    @Mock
//    private ResponseEntityTransformer responseEntityTransformer;
//
//    @Mock
//    private SampleResponseTransformer sampleResponseTransformer;
//
//    @Mock
//    private RequestEntityValidator validator;
//    @Mock
//    private ModelMapper modelMapper;
//
//    @InjectMocks
//    private IntegratorController integratorController;
//
//    @BeforeEach
//    public void setUp() {
//        MockitoAnnotations.initMocks(this);
//    }
//
//    @ParameterizedTest
//    @MethodSource("provideHttpStatusCodes")
//    void testIntegration(String statusCode, HttpStatus expectedHttpStatus) throws Exception {
//        SampleRequestEntity sampleRequestEntity = new SampleRequestEntity();
//        HttpServletRequest request = mock(HttpServletRequest.class);
//        BindingResult bindingResult = mock(BindingResult.class);
//        SampleDomainRequestEntity sampleDomainRequestEntity = new SampleDomainRequestEntity();
//        SampleDomainResponseEntity sampleDomainResponseEntity = getSampleDomainResponseEntity(statusCode);
//        Map<String, Object> responseEntityMap = new HashMap<>();
//        doNothing().when(validator).validate(any());
//        when(bindingResult.hasErrors()).thenReturn(false);
//        when(modelMapper.map(any(), any())).thenReturn(sampleDomainRequestEntity);
//        when(sampleManageService.process(any())).thenReturn(sampleDomainResponseEntity);
//        when(responseEntityTransformer.transform(any(), any())).thenReturn(Arrays.asList(responseEntityMap));
//
//        ResponseEntity<Object> response = integratorController.integration(sampleRequestEntity, request);
//
//        verify(validator).validate(sampleRequestEntity);
//        verify(modelMapper).map(sampleRequestEntity, SampleDomainRequestEntity.class);
//        verify(sampleManageService).process(sampleDomainRequestEntity);
//        verify(responseEntityTransformer).transform(sampleDomainResponseEntity, sampleResponseTransformer);
//
//        assertEquals(expectedHttpStatus, response.getStatusCode());
//    }
//
//    private static Object[][] provideHttpStatusCodes() {
//        return new Object[][]{
//                {"200", HttpStatus.OK},
//                {"202", HttpStatus.ACCEPTED},
//                {"400", HttpStatus.BAD_REQUEST},
//                {"404", HttpStatus.NOT_FOUND},
//                {"500", HttpStatus.INTERNAL_SERVER_ERROR}
//        };
//    }
//
//
//    private SampleDomainResponseEntity getSampleDomainResponseEntity(String respCode) {
//        SampleDomainResponseEntity sampleDomainResponseEntity = new SampleDomainResponseEntity();
//        sampleDomainResponseEntity.setResCode(respCode);
//        return sampleDomainResponseEntity;
//    }
//}
