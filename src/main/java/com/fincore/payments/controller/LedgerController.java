package com.fincore.payments.controller;

import com.fincore.payments.dto.LedgerAccountBalanceResponse;
import com.fincore.payments.dto.LedgerTransactionResponse;
import com.fincore.payments.service.MerchantPaymentService;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class LedgerController {
    private final MerchantPaymentService ledgerService;

    public LedgerController(MerchantPaymentService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/api/v1/payments/{paymentId}/ledger")
    public List<LedgerTransactionResponse> getPaymentLedger(@PathVariable UUID paymentId) {
        return ledgerService.getPaymentLedger(paymentId);
    }

    @GetMapping("/api/v1/ledger/accounts/{currency}/balances")
    public List<LedgerAccountBalanceResponse> getBalances(
            @PathVariable @Pattern(regexp = "[A-Z]{3}",
                    message = "Currency must contain exactly three uppercase letters.") String currency) {
        return ledgerService.getBalances(currency);
    }
}
