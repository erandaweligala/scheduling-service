package com.axonect.aee.template.baseapp.domain.util;

public final class LogMessages {

    private LogMessages() {
        throw new IllegalStateException("Utility class");
    }

    // Error codes
    public static final String ERROR_NOT_FOUND = "AAA_404_NOT_FOUND";
    public static final String ERROR_INTERNAL_ERROR = "AAA_500_INTERNAL_ERROR";
    public static final String ERROR_POLICY_CONFLICT = "AAA_POLICY_CONFLICT";

    public static final String PLAN_DOES_NOT_EXIST = "\"PLAN_DOES_NOT_EXIST\"";
}
