package com.fincore.payments.service;

import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.OutboxEventResponse;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.OutboxRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OutboxService {
    private final OutboxRepository outbox;
    private final PaymentRepository payments;

    public OutboxService(OutboxRepository outbox, PaymentRepository payments) {
        this.outbox = outbox;
        this.payments = payments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Payment payment) {
        outbox.save(PaymentEvent.from(payment));
    }

    @Transactional(readOnly = true)
    public List<OutboxEventResponse> getPaymentEvents(UUID paymentId) {
        if (!payments.existsById(paymentId)) throw new PaymentNotFoundException(paymentId);
        return outbox.findByPaymentId(paymentId);
    }
}
