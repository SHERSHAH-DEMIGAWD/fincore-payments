package com.fincore.payments.repository;

import com.fincore.payments.domain.LedgerAccount;
import com.fincore.payments.domain.LedgerEntry;
import com.fincore.payments.domain.LedgerOperation;
import com.fincore.payments.domain.LedgerSide;
import com.fincore.payments.domain.LedgerSource;
import com.fincore.payments.domain.LedgerTransaction;
import com.fincore.payments.dto.LedgerAccountBalanceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(LedgerTransaction transaction) {
        // Provision in the SAME order for capture/refund to avoid first-use account lock inversions.
        for (LedgerAccount account : transaction.entries().stream().map(LedgerEntry::account).distinct().sorted().toList()) {
            jdbc.update("""
                    INSERT INTO ledger_accounts (account_code, currency, normal_side, created_at)
                    VALUES (?, ?, ?, ?) ON CONFLICT (account_code, currency) DO NOTHING
                    """, account.name(), transaction.currency(), account.normalSide().name(),
                    Timestamp.from(transaction.createdAt()));
        }
        jdbc.update("""
                INSERT INTO ledger_transactions
                    (id, payment_id, operation, currency, amount, reverses_transaction_id, source, created_at, amount_operation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transaction.id(), transaction.paymentId(), transaction.operation().name(),
                transaction.currency(), transaction.amount(), transaction.reversesTransactionId(),
                transaction.source().name(), Timestamp.from(transaction.createdAt()), transaction.amountOperationId());
        for (LedgerEntry entry : transaction.entries()) {
            jdbc.update("""
                    INSERT INTO ledger_entries (id, transaction_id, account_code, currency, side, amount)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, entry.id(), transaction.id(), entry.account().name(), entry.currency(),
                    entry.side().name(), entry.amount());
        }
    }

    public Optional<UUID> findCaptureId(UUID paymentId) {
        return jdbc.query("SELECT id FROM ledger_transactions WHERE payment_id = ? AND operation = 'CAPTURE'",
                (row, number) -> row.getObject("id", UUID.class), paymentId).stream().findFirst();
    }

    public List<LedgerTransaction> findByPaymentId(UUID paymentId) {
        // Amount-managed payments have at most 100 commands and 200 immutable journals.
        return jdbc.query("""
                SELECT * FROM ledger_transactions WHERE payment_id = ?
                ORDER BY created_at, CASE operation WHEN 'CAPTURE' THEN 0 ELSE 1 END, id
                """, (row, number) -> new LedgerTransaction(row.getObject("id", UUID.class), paymentId,
                LedgerOperation.valueOf(row.getString("operation")), row.getString("currency"),
                row.getBigDecimal("amount"), row.getObject("reverses_transaction_id", UUID.class),
                LedgerSource.valueOf(row.getString("source")), row.getTimestamp("created_at").toInstant(),
                entries(row.getObject("id", UUID.class)),row.getObject("amount_operation_id",UUID.class)), paymentId);
    }

    private List<LedgerEntry> entries(UUID transactionId) {
        return jdbc.query("""
                SELECT * FROM ledger_entries WHERE transaction_id = ?
                ORDER BY CASE side WHEN 'DEBIT' THEN 0 ELSE 1 END
                """, (row, number) -> new LedgerEntry(row.getObject("id", UUID.class),
                LedgerAccount.valueOf(row.getString("account_code")), row.getString("currency"),
                LedgerSide.valueOf(row.getString("side")), row.getBigDecimal("amount")), transactionId);
    }

    public List<LedgerAccountBalanceResponse> balances(String currency) {
        return jdbc.query("""
                SELECT a.account_code, a.currency, a.normal_side,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0) AS total_debits,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0) AS total_credits
                FROM ledger_accounts a LEFT JOIN ledger_entries e
                  ON e.account_code = a.account_code AND e.currency = a.currency
                WHERE a.currency = ? GROUP BY a.account_code, a.currency, a.normal_side
                ORDER BY a.account_code
                """, (row, number) -> {
                    LedgerSide normalSide = LedgerSide.valueOf(row.getString("normal_side"));
                    BigDecimal debits = row.getBigDecimal("total_debits").setScale(2);
                    BigDecimal credits = row.getBigDecimal("total_credits").setScale(2);
                    BigDecimal balance = normalSide == LedgerSide.DEBIT
                            ? debits.subtract(credits) : credits.subtract(debits);
                    return new LedgerAccountBalanceResponse(LedgerAccount.valueOf(row.getString("account_code")),
                            currency, normalSide, debits, credits, balance);
                }, currency);
    }

    /** Merchant totals are derived from owned payment journals; pooled account definitions are unchanged. */
    public List<LedgerAccountBalanceResponse> merchantBalances(UUID merchantId,String currency) {
        return jdbc.query("""
                SELECT a.account_code,a.normal_side,
                    COALESCE(SUM(e.amount) FILTER (WHERE e.side='DEBIT'),0) AS total_debits,
                    COALESCE(SUM(e.amount) FILTER (WHERE e.side='CREDIT'),0) AS total_credits
                FROM ledger_accounts a JOIN ledger_entries e
                    ON e.account_code=a.account_code AND e.currency=a.currency
                JOIN ledger_transactions t ON t.id=e.transaction_id
                JOIN payments p ON p.id=t.payment_id
                WHERE p.merchant_id=? AND a.currency=?
                GROUP BY a.account_code,a.normal_side ORDER BY a.account_code
                """,(row,n) -> {
                    var side=LedgerSide.valueOf(row.getString("normal_side"));
                    var debits=row.getBigDecimal("total_debits").setScale(2);
                    var credits=row.getBigDecimal("total_credits").setScale(2);
                    return new LedgerAccountBalanceResponse(LedgerAccount.valueOf(row.getString("account_code")),currency,
                            side,debits,credits,side==LedgerSide.DEBIT ? debits.subtract(credits) : credits.subtract(debits));
                },merchantId,currency);
    }
}
