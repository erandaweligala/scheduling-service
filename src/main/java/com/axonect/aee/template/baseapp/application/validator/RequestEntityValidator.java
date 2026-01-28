/**
 * Copyrights 2023 Axiata Digital Labs Pvt Ltd.
 * All Rights Reserved.
 * <p>
 * These material are unpublished, proprietary, confidential source
 * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
 * SECRET of ADL.
 * <p>
 * ADL retains all title to and intellectual property rights in these
 * materials.
 */
package com.axonect.aee.template.baseapp.application.validator;

import com.axonect.aee.template.baseapp.application.exception.type.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RequestEntityValidator {

    @Autowired
    private Validator validator;

    public void validate(RequestEntityInterface target) throws ValidationException {

        Set<ConstraintViolation<RequestEntityInterface>> errors = this.validator.validate(target);

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

}