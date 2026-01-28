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
//package com.axonect.aee.template.baseapp.application.transport.response.transformers;
//
//import com.axonect.aee.template.baseapp.application.transformer.ResponseEntityInterface;
//import com.axonect.aee.template.baseapp.domain.entities.dto.SampleDomainResponseEntity;
//import org.springframework.stereotype.Component;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Component
//public class SampleResponseTransformer implements ResponseEntityInterface {
//
//    @Override
//    public Map transform(Object entity) {
//        SampleDomainResponseEntity offerResponse = (SampleDomainResponseEntity) entity;
//        Map<String, Object> mapping = new HashMap<>();
//        mapping.put("resCode", offerResponse.getResCode());
//        mapping.put("resDesc", offerResponse.getResDesc());
//
//        return mapping;
//    }
//
//}
