package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.regex.Pattern;

/** One narrow PostgreSQL dump/reparse equivalence; never removes arbitrary casts or SQL whitespace. */
final class RecoverySchema {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String LITERAL="'(?:[^']|'')*'";
    private static final String ITEM=LITERAL+"::character varying(?:::text)?";
    private static final Pattern ARRAY=Pattern.compile("ARRAY\\[((?:"+ITEM+")(?:, "+ITEM+")*)\\](::text\\[\\])?");
    private static final Pattern ELEMENT=Pattern.compile("("+LITERAL+")::character varying(?:::text)?");
    private RecoverySchema() { }

    static String row(String json) {
        try {
            var row=(ArrayNode)JSON.readTree(json);
            if(row.get(0).asText().equals("constraint") || row.get(0).asText().equals("index"))
                row.set(2,JSON.getNodeFactory().textNode(definition(row.get(2).asText())));
            return JSON.writeValueAsString(row);
        }catch(java.io.IOException error){throw new IllegalArgumentException(error);}
    }

    static String definition(String sql) {
        var result=new StringBuilder();int i=0;
        while(i<sql.length()) {
            char character=sql.charAt(i);
            // Deparsed definitions contain standard/E string literals and quoted identifiers.
            if(character=='\'' || character=='"') {
                boolean escaped=character=='\'' && i>0 && (sql.charAt(i-1)=='E' || sql.charAt(i-1)=='e')
                        && (i==1 || !identifier(sql.charAt(i-2)));
                result.append(character);i++;
                while(i<sql.length()) {
                    char c=sql.charAt(i++);result.append(c);
                    if(escaped && c=='\\' && i<sql.length()){result.append(sql.charAt(i++));continue;}
                    if(c==character){if(i<sql.length() && sql.charAt(i)==character)result.append(sql.charAt(i++));else break;}
                }
                continue;
            }
            // No normalization inside comments or dollar-quoted text; preserve the remaining definition.
            if(sql.startsWith("--",i) || sql.startsWith("/*",i) || character=='$'){result.append(sql.substring(i));break;}
            if(sql.startsWith("ARRAY[",i) && (i==0 || !identifier(sql.charAt(i-1)))) {
                var array=ARRAY.matcher(sql);array.region(i,sql.length());
                if(array.lookingAt()) {
                    var elements=ELEMENT.matcher(array.group(1));var normalized=new StringBuilder("ARRAY[");boolean allText=true,first=true;
                    while(elements.find()) {
                        if(!first)normalized.append(", ");first=false;
                        normalized.append(elements.group(1)).append("::text");allText&=elements.group().endsWith("::text");
                    }
                    // Only unbounded varchar literals whose final type is explicitly text/text[].
                    if(array.group(2)!=null || allText){result.append(normalized).append(']');i=array.end();continue;}
                }
            }
            result.append(character);i++;
        }
        return result.toString();
    }
    private static boolean identifier(char c){return Character.isLetterOrDigit(c) || c=='_' || c=='$';}
}
