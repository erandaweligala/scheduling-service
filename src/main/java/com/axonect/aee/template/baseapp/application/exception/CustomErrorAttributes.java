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
package com.axonect.aee.template.baseapp.application.exception;

import com.adl.et.telco.dte.plugin.alarming.dto.AlarmDef;
import com.axonect.aee.template.baseapp.application.alarm.AlarmGen;
import com.axonect.aee.template.baseapp.application.exception.type.BaseException;
import com.axonect.aee.template.baseapp.application.exception.type.ValidationException;
import com.axonect.aee.template.baseapp.application.validator.RequestEntityInterface;
import com.axonect.aee.template.baseapp.external.exception.WebClientException;
import jakarta.validation.ConstraintViolation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


@Component
@SuppressWarnings("java:S1168")
public class CustomErrorAttributes extends DefaultErrorAttributes {
    private static final String MESSAGE_KEY = "message";

    @Autowired
    AlarmGen alarm;

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        boolean includeStackTrace = options.isIncluded(ErrorAttributeOptions.Include.STACK_TRACE);
        Throwable error = getError(webRequest);
        if (error == null) {
            return null;
        }
        switch (error.getClass().getSimpleName()) {
            case "ValidationException":
                return handleValidationException((ValidationException) error);
            case "ControllerException", "FilterException", "DomainException", "WebClientException":
                return handleRecoverableException((BaseException) error, includeStackTrace);
            default:
                return handleGenericException(error, includeStackTrace);
        }
    }

    /**
     * Handle validation exceptions
     *
     * @param error exception
     * @return error description
     */
    private Map<String, Object> handleValidationException(ValidationException error) {

        Map<String, Object> errorDetails = new LinkedHashMap<>();

        errorDetails.put("code", "400");
        errorDetails.put("type", error.getClass().getSimpleName());
        errorDetails.put(MESSAGE_KEY, this.formatValidationErrors(error.getErrors()));

        alarm.alert(AlarmDef.MessageType.FUNCTIONAL, error.getMessage());
        return errorDetails;
    }

    /**
     * Handle unrecoverable and more generic exceptions
     *
     * @param error exception
     * @return error description
     */
    private Map<String, Object> handleGenericException(Throwable error, boolean includeStackTrace) {

        Map<String, Object> errorDetails = new LinkedHashMap<>();

        errorDetails.put("code", "500");
        errorDetails.put("type", error.getClass().getSimpleName());
        errorDetails.put(MESSAGE_KEY, error.getMessage());

        if (includeStackTrace) {
            errorDetails.put("trace", this.getStackTrace(error));
        }

        if (error instanceof WebClientException) {
            alarm.alert(AlarmDef.MessageType.API, error.getMessage());
        } else {
            alarm.alert(AlarmDef.MessageType.FUNCTIONAL, error.getMessage());
        }

        return errorDetails;
    }

    /**
     * Handle recoverable exceptions
     *
     * @param error exception
     * @return error description
     */
    private Map<String, Object> handleRecoverableException(BaseException error,
                                                           boolean includeStackTrace) {

        Map<String, Object> errorDetails = new LinkedHashMap<>();

        errorDetails.put("code", error.getCode() != null ? error.getCode() : "400");
        errorDetails.put("type", error.getClass().getSimpleName());
        errorDetails.put(MESSAGE_KEY, error.getMessage());

        if (includeStackTrace) {
            errorDetails.put("trace", this.getStackTrace(error));
        }

        alarm.alert(AlarmDef.MessageType.FUNCTIONAL, error.getMessage());
        return errorDetails;
    }


    /**
     * Get stack trace from an exception
     *
     * @param error exception
     * @return stack trace
     */
    private String getStackTrace(Throwable error) {

        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        stackTrace.flush();

        return stackTrace.toString();
    }


    private Map<String, ArrayList<String>> formatValidationErrors(Set<ConstraintViolation<RequestEntityInterface>> errors) {

        Map<String, ArrayList<String>> errDetails = new LinkedHashMap<>();

        errors.forEach(error -> {

            String key = error.getPropertyPath().toString();
            String val = error.getMessage();

            // when a validation error already exists for the field
            if (errDetails.containsKey(key)) {

                ArrayList<String> arrVal = errDetails.get(key);
                arrVal.add(val);

                errDetails.put(key, arrVal);

                return;
            }

            // when there are no validation errors for the field
            ArrayList<String> arr = new ArrayList<>();
            arr.add(val);

            errDetails.put(key, arr);
        });

        return errDetails;
    }

}
