package com.axonect.aee.template.baseapp.domain.util;

public final class Constants {

    private Constants() {
        throw new IllegalStateException("Utility class");
    }

    public static final String ACTIVE = "ACTIVE";
    public static final String PENDING = "PENDING";
    public static final String CARRY_FORWARD_BUCKET = "CARRY_FORWARD_BUCKET";


    public static final String SL_TIME_ZONE = "Asia/Colombo";

    public static final String DELETION_REASON_CYCLE_END = "CYCLE_END_DATE_EXPIRED";
    public static final String DELETION_REASON_EXPIRY = "EXPIRY_DATE_EXPIRED";
}
