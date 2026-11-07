package com.fincore.payments.dto;

import java.util.UUID;

public record ShadowRebuildStepResult(UUID runId, long generation, int appliedThisStep, ShadowRebuildView run) { }
