package com.fincore.payments.dto;

import java.util.UUID;

/** Retirement stops live maintenance; all receipts, manifests and decisions are retained. */
public record ProjectionTargetView(UUID runId, String consumerName, long buildGeneration, String lifecycle) { }
