package com.fincore.payments.controller;

import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.service.SettlementService;
import com.fincore.payments.settlement.SettlementImportVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/settlement-statements")
public class SettlementController {
    private final SettlementService service;private final SettlementImportVerifier verifier;
    public SettlementController(SettlementService service,SettlementImportVerifier verifier){this.service=service;this.verifier=verifier;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ImportResult importStatement(HttpServletRequest request){return service.importStatement(verifier.verify(request));}
    @GetMapping public StatementPage list(@RequestParam(required=false)UUID afterStatementId,@RequestParam(defaultValue="50")int limit){return service.list(afterStatementId,limit);}
    @GetMapping("/{statementId}") public Metadata get(@PathVariable UUID statementId){return service.get(statementId);}
    @GetMapping("/{statementId}/document") public ResponseEntity<byte[]> document(@PathVariable UUID statementId){
        var stored=service.document(statementId);byte[] bytes=stored.body().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(bytes.length).cacheControl(CacheControl.noStore())
            .header("Content-Disposition","attachment; filename=\"settlement-"+statementId+".json\"")
            .header("X-Content-SHA256",stored.metadata().sha256()).header("Fincore-Settlement-Key-Id",stored.keyId())
            .header("Fincore-Settlement-Signature",stored.signature()).body(bytes);
    }
    @PostMapping("/{statementId}/reports") public ReportResult reconcile(@PathVariable UUID statementId,@Valid @RequestBody Reconcile command){return service.reconcile(statementId,command);}
    @GetMapping("/{statementId}/reports") public ReportPage reports(@PathVariable UUID statementId,@RequestParam(required=false)UUID afterReportId,
            @RequestParam(defaultValue="50")int limit){return service.reports(statementId,afterReportId,limit);}
    @GetMapping("/{statementId}/reports/{reportId}") public Report report(@PathVariable UUID statementId,@PathVariable UUID reportId){return service.report(statementId,reportId);}
    @PostMapping("/{statementId}/reports/{reportId}/resolve") public ResolutionResult resolve(@PathVariable UUID statementId,@PathVariable UUID reportId,
            @Valid @RequestBody Resolve command){return service.resolve(statementId,reportId,command);}
    @GetMapping("/{statementId}/reports/{reportId}/resolution") public Resolution resolution(@PathVariable UUID statementId,@PathVariable UUID reportId){return service.resolution(statementId,reportId);}
}
