package com.fincore.payments.service;
import com.fincore.payments.dto.PaymentAccounting.*;
import com.fincore.payments.exception.PaymentAccountingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class PaymentAccountingMoneyTest {
    @ParameterizedTest @ValueSource(strings={"0","-1","1.001","1E+2147483647","1E-2147483647","100000000000000000"})
    void rejectsInvalidPrincipalWithoutRescalingHugeExponents(String amount) {
        assertThatThrownBy(()->PaymentAccountingService.normalize(new Command(UUID.randomUUID(),1,Action.CAPTURE,new BigDecimal(amount),BigDecimal.ZERO,null))).isInstanceOf(PaymentAccountingException.class);
    }
    @Test void normalizesExactAmountsWithoutRounding() {
        var c=PaymentAccountingService.normalize(new Command(UUID.randomUUID(),1,Action.CAPTURE,new BigDecimal("1.000"),new BigDecimal("0.0100"),null));
        assertThat(c.amount()).isEqualTo(new BigDecimal("1.00")); assertThat(c.fee()).isEqualTo(new BigDecimal("0.01"));
    }
    @Test void refusesFeeAbovePrincipalAndFeeOnRefund() {
        assertThatThrownBy(()->PaymentAccountingService.normalize(new Command(UUID.randomUUID(),1,Action.CAPTURE,BigDecimal.ONE,BigDecimal.TEN,null))).isInstanceOf(PaymentAccountingException.class);
        assertThatThrownBy(()->PaymentAccountingService.normalize(new Command(UUID.randomUUID(),1,Action.REFUND,BigDecimal.ONE,BigDecimal.ONE,UUID.randomUUID()))).isInstanceOf(PaymentAccountingException.class);
    }
}
