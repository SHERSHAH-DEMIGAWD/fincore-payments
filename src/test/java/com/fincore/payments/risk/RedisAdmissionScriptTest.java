package com.fincore.payments.risk;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Executes the actual Lua under LuaJ with a small command fake; does not claim real Redis coverage. */
class RedisAdmissionScriptTest {
    static class Engine {
        final Globals globals=JsePlatform.standardGlobals();final Map<String,String> fields=new HashMap<>();
        long now=1000,expiry;boolean corruptType;
        Engine(){var redis=new LuaTable();redis.set("call",new VarArgFunction(){@Override public Varargs invoke(Varargs a){
            if(expiry>0 && now>=expiry){fields.clear();expiry=0;}
            return switch(a.arg(1).checkjstring()){
                case "EXISTS" -> LuaValue.valueOf(!fields.isEmpty() || corruptType?1:0);
                case "PTTL" -> LuaValue.valueOf(expiry==0?-1:expiry-now);
                case "HGET" -> {if(corruptType)throw new LuaError("WRONGTYPE");var s=fields.get(a.arg(3).checkjstring());yield s==null?LuaValue.NIL:LuaValue.valueOf(s);}
                case "HSET" -> {for(int i=3;i<=a.narg();i+=2)fields.put(a.arg(i).checkjstring(),a.arg(i+1).tojstring());yield LuaValue.ONE;}
                case "PEXPIRE" -> {expiry=now+a.arg(3).checklong();yield LuaValue.ONE;}
                default -> throw new LuaError("Unsupported command");};
        }});redis.set("error_reply",new VarArgFunction(){@Override public Varargs invoke(Varargs a){throw new LuaError(a.arg(1).checkjstring());}});globals.set("redis",redis);}
        List<Long> reserve(int limit,int window,String id)throws Exception{
            var keys=new LuaTable();keys.set(1,"unit-key");globals.set("KEYS",keys);
            var args=new LuaTable();args.set(1,Integer.toString(limit));args.set(2,Integer.toString(window));args.set(3,id);globals.set("ARGV",args);
            try(var input=getClass().getResourceAsStream("/redis/payment-admission.lua")){
                var result=globals.load(new String(input.readAllBytes(),StandardCharsets.UTF_8),"admission").call();
                return List.of(result.get(1).checklong(),result.get(2).checklong(),result.get(3).checklong());}
        }
    }
    static String id(int i){return String.format("%064x",i);}
    @Test void admissionCapAndExactIntentRetryDoNotDoubleCharge()throws Exception{
        var e=new Engine();assertThat(e.reserve(2,1000,id(1))).containsExactly(1L,1L,1000L);
        assertThat(e.reserve(2,1000,id(1))).containsExactly(1L,1L,1000L);
        assertThat(e.reserve(2,1000,id(2))).containsExactly(1L,2L,1000L);
        assertThat(e.reserve(2,1000,id(3))).containsExactly(0L,2L,1000L);
        assertThat(e.fields).hasSize(3);
    }
    @Test void expiryStartsAtFirstAdmissionAndIsNotExtendedByTraffic()throws Exception{
        var e=new Engine();e.reserve(2,1000,id(1));e.now+=999;
        assertThat(e.reserve(2,1000,id(2))).containsExactly(1L,2L,1L);
        assertThat(e.reserve(2,1000,id(3))).containsExactly(0L,2L,1L);e.now++;
        assertThat(e.reserve(2,1000,id(3))).containsExactly(1L,1L,1000L);
    }
    @Test void hashCardinalityStaysBoundedEvenForManyDeniedIntents()throws Exception{
        var e=new Engine();for(int i=1;i<=1000;i++)e.reserve(1000,1000,id(i));
        for(int i=1001;i<=1050;i++)assertThat(e.reserve(1000,1000,id(i)).getFirst()).isZero();
        assertThat(e.fields).hasSize(1001);
    }
    @Test void corruptPersistentOrOverBudgetStateFailsClosed()throws Exception{
        var e=new Engine();e.fields.put("used","1");assertThatThrownBy(()->e.reserve(2,1000,id(1))).hasMessageContaining("INVALID_ADMISSION_STATE");
        e.expiry=e.now+1000;e.fields.put("used","3");assertThatThrownBy(()->e.reserve(2,1000,id(1))).hasMessageContaining("INVALID_ADMISSION_STATE");
        e.fields.put("used","1");e.fields.put("r:"+id(1),"9");assertThatThrownBy(()->e.reserve(2,1000,id(1))).hasMessageContaining("INVALID_ADMISSION_STATE");
    }
    @Test void invalidArgumentsDoNotWrite()throws Exception{
        var e=new Engine();assertThatThrownBy(()->e.reserve(0,1000,id(1))).hasMessageContaining("INVALID_ADMISSION_POLICY");
        assertThatThrownBy(()->e.reserve(2,999,id(1))).hasMessageContaining("INVALID_ADMISSION_POLICY");
        assertThatThrownBy(()->e.reserve(2,1000,"wrong")).hasMessageContaining("INVALID_ADMISSION_ID");assertThat(e.fields).isEmpty();
    }
}
