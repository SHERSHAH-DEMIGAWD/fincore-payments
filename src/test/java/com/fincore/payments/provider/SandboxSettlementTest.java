package com.fincore.payments.provider;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.settlement.SettlementWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class SandboxSettlementTest {
    @TempDir Path directory;
    static final String API="test-only-provider-secret-0123456789",SIGN="test-only-settlement-secret-0123456789";
    final UUID instance=UUID.randomUUID(),merchant=UUID.randomUUID(),payment=UUID.randomUUID();
    Request command(Action action,Scenario scenario){return new Request(1,instance,UUID.randomUUID(),payment,merchant,action,scenario,BigDecimal.TEN,"INR",action==Action.AUTHORIZE?"":"sandbox-pay-"+payment);}
    @Test void exportedIndependentHistoryIsScopedSignedAndStableAcrossProviderRestart()throws Exception{
        try(var store=new SandboxProviderStore(directory,instance)){
            store.execute(command(Action.AUTHORIZE,Scenario.APPROVE));store.execute(command(Action.CAPTURE,Scenario.DECLINE));store.execute(command(Action.CAPTURE,Scenario.APPROVE));}
        byte[] original;String signature;
        try(var server=new SandboxProviderServer(0,directory,instance,API,null,"sandbox-v1",SIGN);var http=HttpClient.newHttpClient()){
            var response=get(http,server.port(),merchant,API);original=response.body();signature=response.headers().firstValue("Fincore-Settlement-Signature").orElseThrow();
            assertThat(response.statusCode()).isEqualTo(200);var s=SettlementWire.read(original);
            assertThat(s.lines()).hasSize(1);assertThat(s.lines().getFirst().sequence()).isEqualTo(3);assertThat(s.net()).isEqualByComparingTo("10");
            assertThat(SettlementWire.authentic(original,"sandbox-v1",signature,SIGN)).isTrue();
            assertThat(SettlementWire.read(get(http,server.port(),UUID.randomUUID(),API).body()).lines()).isEmpty();
            assertThat(get(http,server.port(),merchant,"wrong").statusCode()).isEqualTo(401);
        }
        try(var server=new SandboxProviderServer(0,directory,instance,API,null,"sandbox-v1",SIGN);var http=HttpClient.newHttpClient()){
            var again=get(http,server.port(),merchant,API);assertThat(again.body()).containsExactly(original);
            assertThat(again.headers().firstValue("Fincore-Settlement-Signature").orElseThrow()).isEqualTo(signature);
        }
        try(var store=new SandboxProviderStore(directory,instance)){store.execute(command(Action.REFUND,Scenario.APPROVE));
            assertThat(store.settlement(merchant,"INR").net()).isZero();assertThat(store.settlement(merchant,"USD").lines()).isEmpty();}
    }
    @Test void exportIsUnavailableWithoutAnExplicitSigningKey()throws Exception{
        try(var server=new SandboxProviderServer(0,directory,instance,API);var http=HttpClient.newHttpClient()){
            assertThat(get(http,server.port(),merchant,API).statusCode()).isEqualTo(503);}
    }
    static HttpResponse<byte[]> get(HttpClient http,int port,UUID merchant,String key)throws Exception{
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/settlements/"+merchant+"/INR"))
                .header("Authorization","Bearer "+key).timeout(java.time.Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
}
