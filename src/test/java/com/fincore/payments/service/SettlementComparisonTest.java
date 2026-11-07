package com.fincore.payments.service;
import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.dto.ProviderOperation.Action;
import com.fincore.payments.repository.SettlementRepository.Local;
import com.fincore.payments.settlement.SettlementWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SettlementComparisonTest {
    final UUID instance=UUID.randomUUID(),merchant=UUID.randomUUID(),payment=UUID.randomUUID(),op=UUID.randomUUID(),journal=UUID.randomUUID();
    BigDecimal amount=new BigDecimal("100.00"),zero=BigDecimal.ZERO.setScale(2);
    Line line(){return new Line(2,op,payment,Action.CAPTURE,amount,zero,"sandbox-pay-"+payment,"sandbox-op-"+op);}
    Statement statement(List<Line> lines){return new Statement(1,SettlementWire.id(instance,merchant,"INR",2),instance,merchant,"INR",2,lines,
        lines.isEmpty()?zero:amount,zero,zero,lines.isEmpty()?zero:amount);}
    Local local(String state){return new Local(op,payment,Action.CAPTURE,amount,"INR",1,state,state.equals("APPLIED")?"APPROVED":state.equals("DECLINED")?"DECLINED":null,
        state.equals("APPLIED")?journal:null,state.equals("APPLIED")?amount:null,state.equals("APPLIED")?"INR":null);}
    @Test void exactIndependentRecordsAgreeWithJournalTotals(){var result=SettlementService.comparison(statement(List.of(line())),List.of(local("APPLIED")));
        assertThat(result.matched()).isTrue();assertThat(result.local().net()).isEqualByComparingTo("100");assertThat(result.items().getFirst().journalId()).isEqualTo(journal);}
    @ParameterizedTest @ValueSource(strings={"READY","IN_FLIGHT","UNKNOWN"})
    void unconfirmedCommandsCannotBecomeMatchedFromAnExternalClaim(String state){var result=SettlementService.comparison(statement(List.of(line())),List.of(local(state)));
        assertThat(result.matched()).isFalse();assertThat(result.items().getFirst().issues()).contains(Issue.LOCAL_UNCONFIRMED);assertThat(result.local().net()).isZero();}
    @Test void absentSidesAndDeclinesHaveDistinctMeaning(){
        assertThat(SettlementService.comparison(statement(List.of(line())),List.of()).items().getFirst().issues()).containsExactly(Issue.UNMATCHED_EXTERNAL);
        assertThat(SettlementService.comparison(statement(List.of()),List.of(local("APPLIED"))).items().getFirst().issues()).containsExactly(Issue.MISSING_EXTERNAL);
        assertThat(SettlementService.comparison(statement(List.of()),List.of(local("DECLINED"))).matched()).isTrue();
        assertThat(SettlementService.comparison(statement(List.of(line())),List.of(local("DECLINED"))).items().getFirst().issues()).contains(Issue.OUTCOME_MISMATCH);
        assertThat(SettlementService.comparison(statement(List.of(line())),List.of(local("REQUIRES_REVIEW"))).items().getFirst().issues()).contains(Issue.LOCAL_REQUIRES_REVIEW);
    }
    @Test void amountFeeIdentityAndJournalDriftAreNeverHiddenByEqualNetTotals(){
        var changed=new Line(2,op,UUID.randomUUID(),Action.REFUND,new BigDecimal("99"),BigDecimal.ONE,"x","y");
        var issues=SettlementService.comparison(statement(List.of(changed)),List.of(local("APPLIED"))).items().getFirst().issues();
        assertThat(issues).contains(Issue.IDENTITY_MISMATCH,Issue.AMOUNT_MISMATCH,Issue.FEE_MISMATCH);
        var missing=new Local(op,payment,Action.CAPTURE,amount,"INR",1,"APPLIED","APPROVED",null,null,null);
        assertThat(SettlementService.comparison(statement(List.of(line())),List.of(missing)).items().getFirst().issues()).contains(Issue.JOURNAL_MISSING);
        var bad=new Local(op,payment,Action.CAPTURE,amount,"INR",1,"APPLIED","APPROVED",journal,BigDecimal.ONE,"USD");
        assertThat(SettlementService.comparison(statement(List.of(line())),List.of(bad)).items().getFirst().issues()).contains(Issue.JOURNAL_MISMATCH);
    }
}
