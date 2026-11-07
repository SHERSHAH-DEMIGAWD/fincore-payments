package com.fincore.payments.settlement;

import com.fincore.payments.config.SettlementProperties;
import com.fincore.payments.dto.Settlement.Statement;
import com.fincore.payments.exception.SettlementException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.util.Collections;

/** Runs in the controller before entering a database transaction. */
@Component
public class SettlementImportVerifier {
    private final SettlementProperties settings;
    public SettlementImportVerifier(SettlementProperties settings){this.settings=settings;}
    public record Verified(Statement statement,byte[] bytes,String keyId,String signature){
        public Verified{bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    public Verified verify(HttpServletRequest request) {
        if(!settings.enabled())throw new SettlementException(503,"Settlement imports are disabled.");
        String key=header(request,"Fincore-Settlement-Key-Id"),signature=header(request,"Fincore-Settlement-Signature");
        if(!settings.keyId().equals(key))throw new SettlementException(401,"Settlement signature is invalid.");
        if(request.getContentLengthLong()>SettlementWire.MAX_BYTES)throw new SettlementException(413,"Settlement byte limit exceeded.");
        try(var input=request.getInputStream()) {
            byte[] bytes=input.readNBytes(SettlementWire.MAX_BYTES+1);
            if(bytes.length>SettlementWire.MAX_BYTES)throw new SettlementException(413,"Settlement byte limit exceeded.");
            if(!SettlementWire.authentic(bytes,key,signature,settings.signingKey()))throw new SettlementException(401,"Settlement signature is invalid.");
            var s=SettlementWire.read(bytes);
            if(!settings.instanceId().equals(s.instanceId()))throw new SettlementException(409,"Settlement provider instance differs.");
            return new Verified(s,bytes,key,signature);
        } catch(java.io.IOException | IllegalArgumentException invalid){throw new SettlementException(400,"Settlement document is invalid.");}
    }
    private static String header(HttpServletRequest request,String name){
        var values=Collections.list(request.getHeaders(name));
        if(values.size()!=1)throw new SettlementException(401,"Exactly one settlement authentication header is required.");
        return values.getFirst();
    }
}
