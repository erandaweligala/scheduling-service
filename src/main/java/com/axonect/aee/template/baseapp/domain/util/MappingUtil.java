package com.axonect.aee.template.baseapp.domain.util;

import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketBalanceUpdate;

/**
 * Utility class for mapping domain objects to persistence payloads.
 */
public class MappingUtil {

    private MappingUtil() {
    }

    /**
     * Build the {@link BucketBalanceUpdate} that persists a balance entry for a terminated session.
     *
     * @param balance the balance to persist
     * @return the bucket instance's final CURRENT_BALANCE and USAGE
     * @throws NumberFormatException if the balance's bucket or service ID is not numeric
     */
    public static BucketBalanceUpdate createBucketBalanceUpdate(Balance balance) {
        long usage;
        long currentBalance;
        if (!balance.isUnlimited()) {
            usage = balance.getInitialBalance() - balance.getQuota();
            currentBalance = balance.getQuota();
        } else {
            usage = balance.getUsage();
            currentBalance = balance.getInitialBalance();
        }
        return new BucketBalanceUpdate(
                Long.parseLong(balance.getBucketId()),
                Long.parseLong(balance.getServiceId()),
                currentBalance,
                usage);
    }
}
