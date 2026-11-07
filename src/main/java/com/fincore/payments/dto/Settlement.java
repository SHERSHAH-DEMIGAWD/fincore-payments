package com.fincore.payments.dto;

import com.fincore.payments.dto.ProviderOperation.Action;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Independent cumulative sandbox observations, never instructions to post money. */
public final class Settlement {
    private Settlement() { }
    public record Line(long sequence,UUID operationId,UUID paymentId,Action action,BigDecimal amount,
            BigDecimal fee,String providerPaymentId,String providerOperationId) { }
    public record Statement(int version,UUID statementId,UUID instanceId,UUID merchantId,String currency,
            long throughSequence,List<Line> lines,BigDecimal captured,BigDecimal refunded,BigDecimal fees,BigDecimal net) {
        public Statement { if (lines!=null) lines=List.copyOf(lines); }
    }
    public record Metadata(UUID statementId,UUID instanceId,String currency,long throughSequence,int lineCount,
            BigDecimal captured,BigDecimal refunded,BigDecimal fees,BigDecimal net,String sha256,Instant importedAt) { }
    public record ImportResult(Metadata statement,boolean replayed) { }
    public record StatementPage(List<Metadata> statements,UUID nextAfterStatementId) { }
    public enum Issue { UNMATCHED_EXTERNAL, MISSING_EXTERNAL, LOCAL_UNCONFIRMED, LOCAL_REQUIRES_REVIEW,
        OUTCOME_MISMATCH, IDENTITY_MISMATCH, AMOUNT_MISMATCH, FEE_MISMATCH, JOURNAL_MISSING, JOURNAL_MISMATCH }
    public record Item(UUID operationId,UUID paymentId,Action action,BigDecimal externalAmount,BigDecimal localAmount,
            BigDecimal externalFee,String localState,UUID journalId,List<Issue> issues) { }
    public record Totals(BigDecimal captured,BigDecimal refunded,BigDecimal fees,BigDecimal net) { }
    public record Comparison(boolean matched,int discrepancyCount,Totals external,Totals local,List<Item> items) { }
    public record Report(UUID reportId,UUID statementId,Instant observedAt,String localFingerprint,Comparison comparison) { }
    public record ReportResult(Report report,boolean replayed) { }
    public record ReportSummary(UUID reportId,UUID statementId,Instant observedAt,boolean matched,int discrepancyCount) { }
    public record ReportPage(List<ReportSummary> reports,UUID nextAfterReportId) { }
    public record Reconcile(@NotNull UUID requestId) { }
    public record Resolve(@NotNull UUID requestId,@NotNull UUID matchedReportId,@NotNull String reason) { }
    public record Resolution(UUID requestId,UUID statementId,UUID discrepantReportId,UUID matchedReportId,
            Instant observedAt,String actor,String reason,String localFingerprint) { }
    public record ResolutionResult(Resolution resolution,boolean replayed) { }
}
