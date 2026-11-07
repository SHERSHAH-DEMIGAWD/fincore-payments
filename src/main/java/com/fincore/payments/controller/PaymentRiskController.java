package com.fincore.payments.controller;
import com.fincore.payments.dto.PaymentRisk.*;
import com.fincore.payments.service.PaymentRiskService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/merchant/risk-decisions")
public class PaymentRiskController {
    private final PaymentRiskService service;
    public PaymentRiskController(PaymentRiskService service){this.service=service;}
    @GetMapping public Page page(@RequestParam(required=false) UUID afterDecisionId,@RequestParam(defaultValue="50") int limit){return service.page(afterDecisionId,limit);}
    @GetMapping("/{decisionId}") public Decision get(@PathVariable UUID decisionId){return service.get(decisionId);}
}
