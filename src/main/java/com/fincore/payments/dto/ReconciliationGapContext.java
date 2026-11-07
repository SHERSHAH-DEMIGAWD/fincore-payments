package com.fincore.payments.dto;

public record ReconciliationGapContext(long configuredOpenQuarantines, long configuredOtherOpenFailures,
        long otherScopeOpenQuarantines, long otherScopeOtherOpenFailures) { }
