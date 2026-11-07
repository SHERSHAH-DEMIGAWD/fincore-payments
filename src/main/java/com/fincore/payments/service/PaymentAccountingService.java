package com.fincore.payments.service;

import com.fincore.payments.config.MerchantContext;
import com.fincore.payments.domain.*;
import com.fincore.payments.dto.PaymentAccounting.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.util.*;

@Service
public class PaymentAccountingService {
    private final PaymentRepository payments;
    private final PaymentAccountingRepository accounting;
    private final LedgerRepository ledger;
    private final OutboxService outbox;
    public PaymentAccountingService(PaymentRepository payments,PaymentAccountingRepository accounting,LedgerRepository ledger,OutboxService outbox) {
        this.payments=payments; this.accounting=accounting; this.ledger=ledger; this.outbox=outbox;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public Result submit(UUID paymentId,Command raw) {
        UUID merchant=MerchantContext.merchantId(); Command command=normalize(raw);
        accounting.lock(merchant,paymentId); Payment payment=owned(merchant,paymentId);
        var saved=accounting.byRequest(merchant,command.requestId());
        if (saved.isPresent()) return replay(payment,command,saved.get());
        if (payments.isProviderManaged(paymentId)) conflict("Amount commands require a manually authorized payment; the provider protocol supports full amounts.");
        if (payment.getVersion()!=command.expectedVersion()) conflict("Payment version changed; fetch accounting before submitting a new command.");
        boolean managed=payments.isAmountManaged(paymentId);
        if (!managed && (payment.getStatus()!=PaymentStatus.AUTHORIZED || accounting.totals(paymentId).captured().signum()!=0))
            conflict("Start amount operations on a manually authorized payment before its first capture.");
        if (accounting.count(paymentId)>=100) conflict("The payment amount-operation limit is 100.");
        var totals=accounting.totals(paymentId); BigDecimal captured=totals.captured(),refunded=totals.refunded();
        UUID reversal=null;
        if (command.action()==Action.CAPTURE) {
            if (refunded.signum()>0 || (payment.getStatus()!=PaymentStatus.AUTHORIZED && payment.getStatus()!=PaymentStatus.PARTIAL_CAPTURE))
                conflict("Capture is closed after full capture or the first refund.");
            captured=captured.add(command.amount());
            if (captured.compareTo(payment.getAmount())>0) conflict("Capture exceeds remaining authorization.");
        } else {
            var capture=accounting.byId(merchant,paymentId,command.captureOperationId()).orElseThrow(()->new PaymentAccountingException(404,"Capture operation was not found."));
            if (capture.action()!=Action.CAPTURE) conflict("Refund must name a capture operation.");
            if (accounting.refundedFor(capture.operationId()).add(command.amount()).compareTo(capture.amount())>0)
                conflict("Refund exceeds remaining capture principal.");
            refunded=refunded.add(command.amount()); reversal=capture.principalJournalId();
        }
        var status=Payment.amountStatus(payment.getAmount(),captured,refunded);
        UUID id=UUID.randomUUID(),principal=UUID.randomUUID(),fee=command.fee().signum()>0 ? UUID.randomUUID() : null;
        if (!managed) accounting.bind(merchant,paymentId,command.expectedVersion(),id);
        if (!accounting.insert(merchant,paymentId,id,command,status,principal,fee))
            return replay(payment,command,accounting.byRequest(merchant,command.requestId()).orElseThrow());
        payment.applyAmounts(captured,refunded); payments.flush();
        ledger.save(LedgerTransaction.amountPosting(principal,payment,id,command.action()==Action.CAPTURE ? LedgerOperation.AMOUNT_CAPTURE : LedgerOperation.AMOUNT_REFUND,command.amount(),reversal));
        if (fee!=null) ledger.save(LedgerTransaction.amountPosting(fee,payment,id,LedgerOperation.FEE,command.fee(),null));
        outbox.record(payment);
        return result(payment,accounting.byId(merchant,paymentId,id).orElseThrow(),false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true)
    public Snapshot get(UUID paymentId) {
        UUID merchant=MerchantContext.merchantId(); var payment=owned(merchant,paymentId);
        return snapshot(payment,accounting.totals(paymentId),payment.getStatus(),payment.getVersion(),payments.isAmountManaged(paymentId));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true)
    public History history(UUID paymentId) {
        UUID merchant=MerchantContext.merchantId(); owned(merchant,paymentId); return new History(accounting.history(merchant,paymentId));
    }
    private Result replay(Payment payment,Command command,Operation old) {
        if (!old.paymentId().equals(payment.getId()) || old.action()!=command.action() || old.sourceVersion()!=command.expectedVersion()
                || old.amount().compareTo(command.amount())!=0 || old.fee().compareTo(command.fee())!=0
                || !Objects.equals(old.captureOperationId(),command.captureOperationId())) conflict("Request UUID was already used for different amount-operation intent.");
        return result(payment,old,true);
    }
    private Result result(Payment payment,Operation op,boolean replayed) {
        return new Result(op,snapshot(payment,accounting.historical(payment.getId(),op.sourceVersion()),op.resultingStatus(),op.resultingVersion(),true),replayed);
    }
    private Snapshot snapshot(Payment payment,PaymentAccountingRepository.Totals t,PaymentStatus status,long version,boolean managed) {
        BigDecimal remaining=(status==PaymentStatus.AUTHORIZED || status==PaymentStatus.PARTIAL_CAPTURE)
                && t.refunded().signum()==0 ? payment.getAmount().subtract(t.captured()) : BigDecimal.ZERO.setScale(2);
        return new Snapshot(payment.getId(),payment.getCurrency(),payment.getAmount(),t.captured(),t.refunded(),t.fees(),
                t.captured().subtract(t.refunded()).subtract(t.fees()),remaining,t.captured().subtract(t.refunded()),status,version,managed);
    }
    private Payment owned(UUID merchant,UUID id) {
        var payment=payments.findById(id).orElseThrow(()->new PaymentNotFoundException(id));
        if (!payment.getMerchantId().equals(merchant)) throw new PaymentNotFoundException(id);
        return payment;
    }
    public static Command normalize(Command c) {
        if (c==null || !identity(c.requestId()) || c.action()==null || c.expectedVersion()<0 || c.expectedVersion()==Long.MAX_VALUE)
            throw new PaymentAccountingException(400,"Explicit request UUID, action and nonnegative version are required.");
        BigDecimal amount=money(c.amount(),false),fee=money(c.fee(),true);
        if ((c.action()==Action.CAPTURE && (c.captureOperationId()!=null || fee.compareTo(amount)>0))
                || (c.action()==Action.REFUND && (!identity(c.captureOperationId()) || fee.signum()!=0)))
            throw new PaymentAccountingException(400,"Capture permits a fee up to principal; refund requires a capture UUID and zero fee.");
        return new Command(c.requestId(),c.expectedVersion(),c.action(),amount,fee,c.captureOperationId());
    }
    private static BigDecimal money(BigDecimal value,boolean zero) {
        try {
            if (value==null || value.signum()<0 || (!zero && value.signum()==0)) throw new ArithmeticException();
            var n=value.stripTrailingZeros();
            if (n.scale()>2 || n.precision()-(long)n.scale()>17) throw new ArithmeticException();
            return n.setScale(2,RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalid) { throw new PaymentAccountingException(400,"Amounts must be exact nonnegative NUMERIC(19,2); principal must be positive."); }
    }
    private static boolean identity(UUID id) { return id!=null && !id.equals(new UUID(0,0)); }
    private static void conflict(String message) { throw new PaymentAccountingException(409,message); }
}
