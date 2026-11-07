package com.fincore.payments.service;

import com.fincore.payments.config.MerchantContext;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.exception.ProviderOperationConflictException;
import com.fincore.payments.provider.*;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class ProviderOperationService {
    private final ProviderOperationTransactions transactions;
    private final PaymentProvider provider;
    public ProviderOperationService(ProviderOperationTransactions transactions,PaymentProvider provider) {
        this.transactions=transactions; this.provider=provider;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result submit(UUID paymentId,Command command) {
        UUID merchant=MerchantContext.merchantId();
        try {
            var prepared=transactions.prepare(merchant,paymentId,command);
            var claim=transactions.claim(merchant,prepared.operation().view().operationId());
            if (claim==null) return new Result(transactions.get(merchant,paymentId,prepared.operation().view().operationId()),prepared.replayed());
            Response response;
            try {
                response=provider.execute(claim.operation().request());
                ProviderWire.validate(response,claim.operation().request());
            } catch (ProviderUncertainException | RuntimeException uncertain) {
                return new Result(transactions.uncertain(merchant,claim.operation().view().operationId(),claim.token()),prepared.replayed());
            }
            return new Result(transactions.finish(merchant,paymentId,claim.operation().view().operationId(),claim.token(),response),prepared.replayed());
        } catch (DataAccessException failure) {
            if (failure instanceof PessimisticLockingFailureException || lockUnavailable(failure))
                throw new ProviderOperationConflictException("Provider operation is busy; retry the exact request.");
            throw failure;
        }
    }
    private static boolean lockUnavailable(Throwable failure) {
        for (Throwable cause=failure;cause!=null;cause=cause.getCause())
            if (cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        return false;
    }
    public View get(UUID paymentId,UUID operationId) { return transactions.get(MerchantContext.merchantId(),paymentId,operationId); }
    public Page page(UUID paymentId,UUID after,int limit) { return transactions.page(MerchantContext.merchantId(),paymentId,after,limit); }
}
