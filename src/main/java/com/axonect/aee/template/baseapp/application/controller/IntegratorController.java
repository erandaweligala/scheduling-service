///**
// * Copyrights 2023 Axiata Digital Labs Pvt Ltd.
// * All Rights Reserved.
// * <p>
// * These material are unpublished, proprietary, confidential source
// * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
// * SECRET of ADL.
// * <p>
// * ADL retains all title to and intellectual property rights in these
// * materials.
// */
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
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.validation.annotation.Validated;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.Map;
//
//@RestController
//@RequestMapping("${base-url.context}")
//public class IntegratorController extends BaseController {
//
//
//    @Autowired
//    SampleManageService sampleManageService;
//
//    @Autowired
//    ResponseEntityTransformer responseEntityTransformer;
//
//    @Autowired
//    SampleResponseTransformer sampleResponseTransformer;
//
//    @Autowired
//    private RequestEntityValidator validator;
//
//    @PostMapping(value = "/action",
//            produces = MediaType.APPLICATION_JSON_VALUE)
//    public ResponseEntity<Object> integration(
//            @Validated
//            @RequestBody(required = true)
//                    SampleRequestEntity sampleRequestEntity, HttpServletRequest request) {
//
////       Validate the request
//        validator.validate(sampleRequestEntity);
//        logger.info("Request validation success");
//
////        Request object map to domain entity object
//        SampleDomainRequestEntity sampleDomainRequestEntity = modelMapper.map(sampleRequestEntity, SampleDomainRequestEntity.class);
//
////        Call domain business logic
//        SampleDomainResponseEntity sampleDomainResponseEntity = sampleManageService.process(sampleDomainRequestEntity);
//
////        Transform domain response
//        Map<String, Object> trResponse = responseEntityTransformer.transform(sampleDomainResponseEntity, sampleResponseTransformer);
//        logger.info("Transformed response : {}", trResponse);
//
////        Return response
//        switch (sampleDomainResponseEntity.getResCode()) {
//            case "200":
//                return ResponseEntity.status(HttpStatus.OK).body(trResponse);
//            case "202":
//                return ResponseEntity.status(HttpStatus.ACCEPTED).body(trResponse);
//            case "400":
//                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(trResponse);
//            case "404":
//                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(trResponse);
//            default:
//                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(trResponse);
//        }
//    }
//
//}