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
//package com.axonect.aee.template.baseapp.domain.service;
//
//import ch.qos.logback.classic.Logger;
//import com.adl.et.telco.dte.plugin.logging.services.LoggingUtils;
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainRequestEntity;
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainResponseEntity;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//
//@Service
//public class SampleManageService {
//
//    private static final Logger logger = LoggingUtils.getLogger(SampleManageService.class.getName());
//
//    @Autowired
//    SampleDomainResponseEntity sampleDomainResponseEntity;
//
//    /**
//     * handle business logic
//     *
//     * @param
//     * @return
//     */
//    public SampleDomainResponseEntity process(SampleDomainRequestEntity sampleDomainRequestEntity) {
//        logger.info("INFO|START use case...| request : {}", sampleDomainRequestEntity);
//
////        Execute business logic
//
//        sampleDomainResponseEntity.setResCode("200");
//        sampleDomainResponseEntity.setResDesc("Operation Success");
//
//        return sampleDomainResponseEntity;
//    }
//
//}
