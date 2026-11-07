package com.fincore.payments.deployment;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Local deployment inputs only. Never prints credentials or accepts arbitrary property names. */
public final class SandboxConfiguration {
    public static final String MERCHANT_A="demo-merchant-a",MERCHANT_B="demo-merchant-b",OBSERVER="demo-observer",OPERATOR="demo-operator";
    public static final Set<String> SECRET_NAMES=Set.of("DB_PASSWORD","FINCORE_DEMO_A_PASSWORD","FINCORE_DEMO_B_PASSWORD",
            "FINCORE_DEMO_OBSERVER_PASSWORD","FINCORE_DEMO_OPERATOR_PASSWORD","FINCORE_OBSERVABILITY_PASSWORD_HASH",
            "FINCORE_OPERATIONS_PASSWORD_HASH","FINCORE_PROVIDER_API_KEY","FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY",
            "FINCORE_SETTLEMENT_SIGNING_KEY","FINCORE_MERCHANT_WEBHOOK_MASTER_KEY","FINCORE_PROVIDER_INSTANCE_ID","FINCORE_DEMO_SUBSCRIPTION_REQUEST_ID");
    private SandboxConfiguration() { }
    public static String secret(Map<String,String> environment,String name)throws Exception {
        if(!SECRET_NAMES.contains(name))throw new IllegalArgumentException();
        String plain=environment.get(name),file=environment.get(name+"_FILE");
        if(plain!=null && file!=null)throw new IllegalArgumentException();
        if(plain!=null)return plain;
        if(file==null)throw new IllegalArgumentException();Path path=Path.of(file);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();
        byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(4097);}
        if(bytes.length==0 || bytes.length>4096)throw new IllegalArgumentException();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static String password(Map<String,String> env,String name)throws Exception {
        String result=secret(env,name);if(!result.matches("[!-~]{32,256}"))throw new IllegalArgumentException();return result;
    }
    static Map<String,String> options(String[] args,Set<String> expected) {
        if(args==null || args.length!=expected.size())throw new IllegalArgumentException();var result=new HashMap<String,String>();
        for(String arg:args){if(arg==null || !arg.startsWith("--"))throw new IllegalArgumentException();int split=arg.indexOf('=');
            if(split<3 || split==arg.length()-1)throw new IllegalArgumentException();String name=arg.substring(2,split);
            if(!expected.contains(name) || result.putIfAbsent(name,arg.substring(split+1))!=null)throw new IllegalArgumentException();}
        if(!result.keySet().equals(expected))throw new IllegalArgumentException();return Map.copyOf(result);
    }
    static URI loopback(String input) {
        URI uri=URI.create(input);
        if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1 || uri.getPort()>65535
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getRawPath().isEmpty())throw new IllegalArgumentException();
        return uri;
    }
    static UUID identity(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value) || id.equals(new UUID(0,0)))throw new IllegalArgumentException();return id;}
}
