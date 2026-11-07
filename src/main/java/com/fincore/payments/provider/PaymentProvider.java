package com.fincore.payments.provider;

import com.fincore.payments.dto.ProviderOperation.*;

public interface PaymentProvider {
    Response execute(Request request) throws ProviderUncertainException;
}
