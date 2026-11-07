package com.fincore.payments.service;

import com.fincore.payments.config.MerchantContext;
import com.fincore.payments.dto.ProviderOperation.Action;
import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.exception.SettlementException;
import com.fincore.payments.repository.SettlementRepository;
import com.fincore.payments.repository.SettlementRepository.*;
import com.fincore.payments.settlement.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class SettlementService {
    private final SettlementRepository repository;
    public SettlementService(SettlementRepository repository){this.repository=repository;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ImportResult importStatement(SettlementImportVerifier.Verified verified){
        UUID merchant=MerchantContext.merchantId();var s=verified.statement();
        if(!merchant.equals(s.merchantId()))throw new SettlementException(404,"Settlement statement was not found.");
        repository.fence(merchant);var old=repository.stored(merchant,s.statementId());
        if(old.isPresent()){
            if(!old.get().metadata().sha256().equals(SettlementWire.sha256(verified.bytes())))conflict("Statement identity has different bytes.");
            return new ImportResult(old.get().metadata(),true);
        }
        repository.insert(merchant,s,verified.bytes(),verified.keyId(),verified.signature());
        return new ImportResult(stored(s.statementId()).metadata(),false);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ReportResult reconcile(UUID statementId,Reconcile command){
        UUID merchant=MerchantContext.merchantId();require(command==null?null:command.requestId());repository.fence(merchant);
        var old=repository.report(merchant,command.requestId());
        if(old.isPresent()){
            if(!old.get().statementId().equals(statementId))conflict("Report request UUID has different intent.");
            return new ReportResult(old.get(),true);
        }
        var evidence=stored(statementId);var current=compare(evidence,merchant);
        var report=new Report(command.requestId(),statementId,repository.observationTime(),current.fingerprint(),current.comparison());
        repository.insertReport(merchant,report);return new ReportResult(report,false);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ResolutionResult resolve(UUID statementId,UUID reportId,Resolve command){
        var principal=MerchantContext.principal();UUID merchant=principal.merchantId();
        require(command==null?null:command.requestId());require(command.matchedReportId());
        String reason=command.reason(),actor=principal.getUsername();
        if(reason==null || reason.isBlank() || reason.length()>300 || reason.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x2028||c==0x2029))
            throw new SettlementException(400,"A bounded single-line reason is required.");
        repository.fence(merchant);var accepted=repository.resolutionRequest(merchant,command.requestId());
        if(accepted.isPresent()){
            var r=accepted.get();if(!r.statementId().equals(statementId)||!r.discrepantReportId().equals(reportId)
                    ||!r.matchedReportId().equals(command.matchedReportId())||!r.reason().equals(reason)||!r.actor().equals(actor))conflict("Resolution request UUID has different intent.");
            return new ResolutionResult(r,true);
        }
        var discrepant=ownedReport(merchant,statementId,reportId);var matched=ownedReport(merchant,statementId,command.matchedReportId());
        if(discrepant.comparison().matched() || !matched.comparison().matched() || repository.resolution(merchant,reportId).isPresent())
            conflict("Resolution needs an unresolved discrepant report and a matching report for the same statement.");
        var fresh=compare(stored(statementId),merchant);
        if(!fresh.comparison().matched() || !fresh.fingerprint().equals(matched.localFingerprint()))conflict("Matching report is stale; reconcile current source first.");
        var result=new Resolution(command.requestId(),statementId,reportId,command.matchedReportId(),repository.observationTime(),actor,reason,fresh.fingerprint());
        repository.insertResolution(merchant,result);return new ResolutionResult(result,false);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public Metadata get(UUID id){return stored(id).metadata();}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public Stored document(UUID id){var result=stored(id);parse(result);return result;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public StatementPage list(UUID after,int limit){page(limit);var rows=repository.statements(MerchantContext.merchantId(),after,limit);
        var selected=rows.stream().limit(limit).toList();return new StatementPage(selected,rows.size()>limit?selected.getLast().statementId():null);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public Report report(UUID statement,UUID report){return ownedReport(MerchantContext.merchantId(),statement,report);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public ReportPage reports(UUID statement,UUID after,int limit){page(limit);stored(statement);var rows=repository.reports(MerchantContext.merchantId(),statement,after,limit);
        var selected=rows.stream().limit(limit).toList();return new ReportPage(selected,rows.size()>limit?selected.getLast().reportId():null);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public Resolution resolution(UUID statement,UUID report){ownedReport(MerchantContext.merchantId(),statement,report);
        return repository.resolution(MerchantContext.merchantId(),report).orElseThrow(()->new SettlementException(404,"Resolution was not found."));}
    private Stored stored(UUID id){return repository.stored(MerchantContext.merchantId(),id).orElseThrow(()->new SettlementException(404,"Settlement statement was not found."));}
    private Report ownedReport(UUID merchant,UUID statement,UUID report){return repository.report(merchant,report).filter(r->r.statementId().equals(statement))
        .orElseThrow(()->new SettlementException(404,"Settlement report was not found."));}
    private static Statement parse(Stored stored){try{
        byte[] bytes=stored.body().getBytes(StandardCharsets.UTF_8);
        if(!SettlementWire.sha256(bytes).equals(stored.metadata().sha256()))throw new IllegalArgumentException();
        return SettlementWire.read(bytes);
    }catch(Exception corrupt){throw new SettlementException(409,"Stored settlement evidence failed integrity verification.");}}
    private record Compared(String fingerprint,Comparison comparison){ }
    private Compared compare(Stored evidence,UUID merchant){
        var statement=parse(evidence);var locals=repository.local(merchant,statement.instanceId(),statement.currency());
        if(locals.size()>1000)conflict("Local settlement comparison exceeds 1000 commands.");
        String fingerprint=SettlementWire.sha256((evidence.metadata().sha256()+"\n"+SettlementRepository.encode(locals)).getBytes(StandardCharsets.UTF_8));
        return new Compared(fingerprint,comparison(statement,locals));
    }
    /** Pure comparison: never applies a provider result or creates a financial journal. */
    public static Comparison comparison(Statement statement,List<Local> locals){
        var external=new HashMap<UUID,Line>();for(var line:statement.lines())external.put(line.operationId(),line);
        var local=new HashMap<UUID,Local>();for(var row:locals)local.put(row.operationId(),row);
        var ids=new TreeSet<UUID>(Comparator.comparing(UUID::toString));ids.addAll(external.keySet());ids.addAll(local.keySet());
        var items=new ArrayList<Item>();BigDecimal captured=zero(),refunded=zero();int discrepancies=0;
        for(var id:ids){
            Line e=external.get(id);Local l=local.get(id);var issues=new ArrayList<Issue>();
            if(l==null)issues.add(Issue.UNMATCHED_EXTERNAL);
            else {
                boolean applied=l.state().equals("APPLIED") && "APPROVED".equals(l.outcome());
                if(l.state().equals("REQUIRES_REVIEW"))issues.add(Issue.LOCAL_REQUIRES_REVIEW);
                else if(!applied && !l.state().equals("DECLINED"))issues.add(Issue.LOCAL_UNCONFIRMED);
                if(e==null){if(applied)issues.add(Issue.MISSING_EXTERNAL);}
                else {
                    if(l.state().equals("DECLINED"))issues.add(Issue.OUTCOME_MISMATCH);
                    if(!l.paymentId().equals(e.paymentId()) || l.action()!=e.action())issues.add(Issue.IDENTITY_MISMATCH);
                    if(l.amount().compareTo(e.amount())!=0)issues.add(Issue.AMOUNT_MISMATCH);
                    if(e.fee().signum()!=0)issues.add(Issue.FEE_MISMATCH);
                }
                if(applied){
                    if(l.journalId()==null)issues.add(Issue.JOURNAL_MISSING);
                    else {
                        if(l.journalAmount().compareTo(l.amount())!=0 || !l.currency().equals(l.journalCurrency()))issues.add(Issue.JOURNAL_MISMATCH);
                        if(l.action()==Action.CAPTURE)captured=captured.add(l.journalAmount());else refunded=refunded.add(l.journalAmount());
                    }
                }
            }
            // Confirmed declines have no financial line; retain them as no-effect observations.
            if(!issues.isEmpty())discrepancies++;
            items.add(new Item(id,e!=null?e.paymentId():l.paymentId(),e!=null?e.action():l.action(),e==null?null:e.amount(),l==null?null:l.amount(),
                    e==null?null:e.fee(),l==null?null:l.state(),l==null?null:l.journalId(),List.copyOf(issues)));
        }
        return new Comparison(discrepancies==0,discrepancies,new Totals(statement.captured(),statement.refunded(),statement.fees(),statement.net()),
                new Totals(captured,refunded,zero(),captured.subtract(refunded)),List.copyOf(items));
    }
    private static BigDecimal zero(){return BigDecimal.ZERO.setScale(2);}
    private static void require(UUID value){if(!SettlementWire.uuid(value))throw new SettlementException(400,"A nonzero request UUID is required.");}
    private static void page(int limit){if(limit<1||limit>100)throw new SettlementException(400,"Page limit must be between 1 and 100.");}
    private static void conflict(String message){throw new SettlementException(409,message);}
}
