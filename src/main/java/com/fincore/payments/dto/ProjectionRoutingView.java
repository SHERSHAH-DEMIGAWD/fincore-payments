package com.fincore.payments.dto;

import java.util.UUID;

public record ProjectionRoutingView(long revision, String activeConsumer, UUID shadowRunId, String shadowConsumer) { }
