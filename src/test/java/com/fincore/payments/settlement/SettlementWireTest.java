package com.fincore.payments.settlement;

import com.fincore.payments.config.SettlementProperties;
import com.fincore.payments.dto.ProviderOperation.Action;
import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.exception.SettlementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SettlementWireTest {
    static final String KEY="test-only-settlement-secret-0123456789",KEY_ID="sandbox-v1";
    final UUID instance=UUID.randomUUID(),merchant=UUID.randomUUID(),payment=UUID.randomUUID();
    static BigDecimal money(String text){return new BigDecimal(text);}
    Line line(long seq){UUID op=UUID.randomUUID();return new Line(seq,op,payment,Action.CAPTURE,money("10.00"),money("0.00"),"sandbox-pay-"+payment,"sandbox-op-"+op);}
    Statement statement(List<Line> lines,long through){var captured=lines.stream().map(Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new Statement(1,SettlementWire.id(instance,merchant,"INR",through),instance,merchant,"INR",through,lines,captured,BigDecimal.ZERO,BigDecimal.ZERO,captured);}
    MockHttpServletRequest request(byte[] bytes){var r=new MockHttpServletRequest();r.setContent(bytes);r.addHeader("Fincore-Settlement-Key-Id",KEY_ID);
        r.addHeader("Fincore-Settlement-Signature",SettlementWire.signature(bytes,KEY_ID,KEY));return r;}
    SettlementImportVerifier verifier(boolean enabled){return new SettlementImportVerifier(new SettlementProperties(enabled,KEY,KEY_ID,instance));}
    @Test void exactBytesAndDomainAreAuthenticated()throws Exception{
        byte[] bytes=SettlementWire.encode(statement(List.of(line(2)),2));
        assertThat(SettlementWire.read(bytes).lines()).hasSize(1);
        assertThat(SettlementWire.authentic(bytes,KEY_ID,SettlementWire.signature(bytes,KEY_ID,KEY),KEY)).isTrue();
        assertThat(SettlementWire.authentic((new String(bytes,StandardCharsets.UTF_8)+"\n").getBytes(StandardCharsets.UTF_8),KEY_ID,SettlementWire.signature(bytes,KEY_ID,KEY),KEY)).isFalse();
        assertThat(SettlementWire.signature(bytes,"other-key",KEY)).isNotEqualTo(SettlementWire.signature(bytes,KEY_ID,KEY));
        var verified=verifier(true).verify(request(bytes));byte[] returned=verified.bytes();returned[0]=0;
        assertThat(verified.bytes()).containsExactly(bytes);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","unknown","coerced","fraction","missing","null","overflow","negative"})
    void strictJsonRejectsAmbiguity(String type)throws Exception{
        String json=new String(SettlementWire.encode(statement(List.of(),0)),StandardCharsets.UTF_8);
        json=switch(type){case "duplicate"->json.replace("\"version\":1","\"version\":1,\"version\":1");
            case "trailing"->json+"{}";case "unknown"->json.replace("\"version\":1","\"extra\":true,\"version\":1");
            case "coerced"->json.replace("\"version\":1","\"version\":\"1\"");case "fraction"->json.replace("\"version\":1","\"version\":1.0");
            case "missing"->json.replace("\"version\":1,","");case "null"->json.replace("\"lines\":[]","\"lines\":null");
            case "overflow"->json.replace("\"captured\":0","\"captured\":1e2147483647");default->json.replace("\"net\":0","\"net\":-1");};
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);assertThatThrownBy(()->SettlementWire.read(bytes)).isInstanceOf(Exception.class);
    }
    @Test void rejectsBadUtf8OversizedFilesDuplicateOperationsAndSequenceDrift()throws Exception{
        assertThatThrownBy(()->SettlementWire.read(new byte[]{(byte)0xc3,(byte)0x28})).isInstanceOf(Exception.class);
        assertThatThrownBy(()->SettlementWire.read(new byte[SettlementWire.MAX_BYTES+1])).isInstanceOf(IllegalArgumentException.class);
        var a=line(2);assertThatThrownBy(()->SettlementWire.encode(statement(List.of(a,a),2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SettlementWire.encode(statement(List.of(a),1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SettlementWire.encode(statement(List.of(line(2),line(1)),2))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactRowBudgetAndNonExpandingMoneyValidation()throws Exception{
        var lines=new ArrayList<Line>();for(int i=1;i<=1000;i++)lines.add(line(i));
        assertThat(SettlementWire.read(SettlementWire.encode(statement(lines,1000))).lines()).hasSize(1000);
        lines.add(line(1001));assertThatThrownBy(()->SettlementWire.encode(statement(lines,1001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SettlementWire.money(money("1e2147483647"),true,17)).isFalse();
        assertThat(SettlementWire.money(money("1e-2147483647"),true,17)).isFalse();
        assertThat(SettlementWire.money(money("10.000"),true,17)).isTrue();
        assertThat(SettlementWire.money(money("10.001"),true,17)).isFalse();
    }
    @Test void headerDuplicationDisabledImportsAndBadMacFailClosed()throws Exception{
        byte[] bytes=SettlementWire.encode(statement(List.of(),0));
        assertThatThrownBy(()->verifier(false).verify(request(bytes))).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(503));
        var duplicate=request(bytes);duplicate.addHeader("Fincore-Settlement-Key-Id",KEY_ID);
        assertThatThrownBy(()->verifier(true).verify(duplicate)).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(401));
        var bad=request(bytes);bad.removeHeader("Fincore-Settlement-Signature");bad.addHeader("Fincore-Settlement-Signature","v1="+"0".repeat(64));
        assertThatThrownBy(()->verifier(true).verify(bad)).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(401));
        var tooLarge=request(new byte[SettlementWire.MAX_BYTES+1]);
        assertThatThrownBy(()->verifier(true).verify(tooLarge)).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(413));
        var foreign=new SettlementImportVerifier(new SettlementProperties(true,KEY,KEY_ID,UUID.randomUUID()));
        assertThatThrownBy(()->foreign.verify(request(bytes))).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(409));
    }
    @Test void authenticatedNoncanonicalUuidFailsAtTheWireBoundary()throws Exception{
        UUID fixed=UUID.fromString("abcdef01-2345-4abc-8def-abcdef012345");
        var s=new Statement(1,SettlementWire.id(fixed,merchant,"INR",0),fixed,merchant,"INR",0,List.of(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);
        byte[] bytes=new String(SettlementWire.encode(s),StandardCharsets.UTF_8).replace(fixed.toString(),fixed.toString().toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
        var verifier=new SettlementImportVerifier(new SettlementProperties(true,KEY,KEY_ID,fixed));
        assertThatThrownBy(()->verifier.verify(request(bytes))).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(400));
    }
    @Test void treeValidationPreservesLargeExactDecimalPrincipal()throws Exception{
        var original=line(1);var amount=money("99999999999999999.99");
        var line=new Line(1,original.operationId(),payment,Action.CAPTURE,amount,money("0.01"),original.providerPaymentId(),original.providerOperationId());
        var s=new Statement(1,SettlementWire.id(instance,merchant,"INR",1),instance,merchant,"INR",1,List.of(line),amount,BigDecimal.ZERO,money("0.01"),money("99999999999999999.98"));
        var parsed=SettlementWire.read(SettlementWire.encode(s));
        assertThat(parsed.lines().getFirst().amount()).isEqualByComparingTo(amount);
        assertThat(parsed.net()).isEqualByComparingTo("99999999999999999.98");
    }
}
