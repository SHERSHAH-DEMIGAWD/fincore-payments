package com.fincore.payments.service;

import com.fincore.payments.config.ProviderProperties;
import com.fincore.payments.domain.MerchantIdentity;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.dto.ProviderWebhook.Application;
import com.fincore.payments.dto.ProviderWebhook.Disposition;
import com.fincore.payments.exception.*;
import com.fincore.payments.provider.ProviderWire;
import com.fincore.payments.repository.ProviderOperationRepository;
import com.fincore.payments.repository.ProviderOperationRepository.Stored;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** Each method commits independently; no database transaction spans provider I/O. */
@Service
public class ProviderOperationTransactions {
    private final ProviderOperationRepository operations;
    private final PaymentService payments;
    private final ProviderProperties properties;
    public ProviderOperationTransactions(ProviderOperationRepository operations,PaymentService payments,ProviderProperties properties) {
        this.operations=operations; this.payments=payments; this.properties=properties;
    }
    public record Prepared(Stored operation,boolean replayed) { }
    public record Claim(Stored operation,UUID token) { }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public Prepared prepare(UUID merchant,UUID payment,Command command) {
        if (command==null || command.requestId()==null || command.action()==null || command.sandboxScenario()==null)
            throw new IllegalArgumentException("Provider command identity and intent are required.");
        var source=operations.source(merchant,payment,true);
        var saved=operations.byRequest(merchant,command.requestId());
        if (saved.isPresent()) {
            sameIntent(saved.get(),payment,command);
            return new Prepared(saved.get(),true);
        }
        requireEnabled(properties.instanceId());
        if (merchant.equals(MerchantIdentity.LEGACY_ID)) conflict("Provider operations require a provisioned merchant.");
        String expected=switch (command.action()) { case AUTHORIZE -> "CREATED"; case CAPTURE -> "AUTHORIZED"; case REFUND -> "CAPTURED"; };
        if (!source.status().equals(expected)) conflict("Payment state does not allow this provider operation.");
        if (operations.unfinished(payment)) conflict("Resolve the existing provider operation using its original request UUID.");
        var binding=operations.binding(payment);
        String remoteId;
        if (command.action()==Action.AUTHORIZE) {
            if (binding.isPresent()) conflict("Payment is already bound to a provider authorization.");
            operations.bind(source,properties.instanceId()); remoteId="";
        } else {
            var enrolled=binding.orElseThrow(() -> new ProviderOperationConflictException("Authorize through the provider before capture or refund."));
            requireEnabled(enrolled.instanceId());
            if (enrolled.providerPaymentId().isEmpty()) conflict("Provider authorization is not confirmed.");
            remoteId=enrolled.providerPaymentId();
        }
        UUID id=UUID.randomUUID();
        if (!operations.insert(id,source,properties.instanceId(),command,remoteId)) {
            var winner=operations.byRequest(merchant,command.requestId()).orElseThrow();
            sameIntent(winner,payment,command);
            return new Prepared(winner,true);
        }
        return new Prepared(operations.byId(merchant,id,false).orElseThrow(),false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public Claim claim(UUID merchant,UUID operationId) {
        var current=operations.byId(merchant,operationId,true).orElseThrow(ProviderOperationNotFoundException::new);
        if (terminal(current.view().state())) return null;
        requireEnabled(current.view().providerInstanceId());
        if (current.view().attempts()>=1000) conflict("Provider retry budget is exhausted; retain the command for review.");
        UUID token=UUID.randomUUID();
        if (!operations.lease(current,token,properties.leaseSeconds())) return null;
        return new Claim(operations.byId(merchant,operationId,false).orElseThrow(),token);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public View uncertain(UUID merchant,UUID operationId,UUID token) {
        var current=operations.byId(merchant,operationId,true).orElseThrow(ProviderOperationNotFoundException::new);
        if (current.view().state()==State.IN_FLIGHT && token.equals(current.leaseToken())) operations.unknown(operationId,token);
        return operations.byId(merchant,operationId,false).orElseThrow().view();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public View finish(UUID merchant,UUID paymentId,UUID operationId,UUID token,Response response) {
        var source=operations.source(merchant,paymentId,true);
        var current=operations.byId(merchant,operationId,true).orElseThrow(ProviderOperationNotFoundException::new);
        if (!current.view().paymentId().equals(paymentId)) throw new ProviderOperationNotFoundException();
        if (current.view().state()!=State.IN_FLIGHT || !token.equals(current.leaseToken())) return current.view();
        ProviderWire.validate(response,current.request());
        return apply(source,current,response);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Application finishWebhook(UUID merchant,UUID paymentId,UUID operationId,Response response) {
        var source=operations.source(merchant,paymentId,true);
        var current=operations.byId(merchant,operationId,true).orElseThrow(ProviderOperationNotFoundException::new);
        if (!current.view().paymentId().equals(paymentId)) throw new ProviderOperationNotFoundException();
        try { ProviderWire.validate(response,current.request()); }
        catch (IllegalArgumentException mismatch) { return webhookResult(Disposition.REJECTED,"INTENT_MISMATCH"); }
        if (terminal(current.view().state())) {
            if (current.view().providerOutcome()!=response.outcome()
                    || !current.view().providerPaymentId().equals(response.providerPaymentId())
                    || !current.view().providerOperationId().equals(response.providerOperationId()))
                return webhookResult(Disposition.REJECTED,"OUTCOME_CONFLICT");
            return current.view().state()==State.REQUIRES_REVIEW ? webhookResult(Disposition.REQUIRES_REVIEW,"LOCAL_STATE_CONFLICT") : webhookResult(Disposition.ALREADY_RECORDED,null);
        }
        if (current.view().state()==State.READY) return webhookResult(Disposition.REJECTED,"NOT_DISPATCHED");
        var applied=apply(source,current,response);
        return applied.state()==State.REQUIRES_REVIEW ? webhookResult(Disposition.REQUIRES_REVIEW,"LOCAL_STATE_CONFLICT") : webhookResult(Disposition.CONFIRMED,null);
    }
    private static Application webhookResult(Disposition disposition,String code) {
        return new Application(disposition,code);
    }
    private View apply(ProviderOperationRepository.Source source,Stored current,Response response) {
        boolean conflict=source.version()!=current.sourceVersion() || !source.status().equals(current.sourceStatus())
                || source.amount().compareTo(current.amount())!=0 || !source.currency().equals(current.currency());
        operations.complete(current,response,conflict);
        if (!conflict && (response.outcome()==Outcome.APPROVED || current.view().action()==Action.AUTHORIZE))
            payments.applyProviderResult(current.view().paymentId(),current.view().operationId(),current.view().action(),response.outcome());
        return operations.byId(current.merchantId(),current.view().operationId(),false).orElseThrow().view();
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public View get(UUID merchant,UUID payment,UUID operationId) {
        operations.source(merchant,payment,false);
        var found=operations.byId(merchant,operationId,false).orElseThrow(ProviderOperationNotFoundException::new);
        if (!found.view().paymentId().equals(payment)) throw new ProviderOperationNotFoundException();
        return found.view();
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Page page(UUID merchant,UUID payment,UUID after,int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Provider page limit must be 1–100.");
        operations.source(merchant,payment,false);
        return operations.page(merchant,payment,after,limit);
    }
    private void requireEnabled(UUID instance) {
        if (!properties.enabled()) conflict("Sandbox provider integration is disabled.");
        if (!properties.instanceId().equals(instance)) conflict("Provider instance changed; restore the original instance before retrying.");
    }
    private static void sameIntent(Stored saved,UUID payment,Command command) {
        if (!saved.view().paymentId().equals(payment) || saved.view().action()!=command.action()
                || saved.view().sandboxScenario()!=command.sandboxScenario()) conflict("Request UUID already has different provider intent.");
    }
    public static boolean terminal(State state) { return state==State.APPLIED || state==State.DECLINED || state==State.REQUIRES_REVIEW; }
    private static void conflict(String message) { throw new ProviderOperationConflictException(message); }
}
