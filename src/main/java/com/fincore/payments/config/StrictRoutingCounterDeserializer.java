package com.fincore.payments.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;

/** Operator revision intent must be an explicit integer, without Jackson's normal scalar coercion. */
public class StrictRoutingCounterDeserializer extends StdDeserializer<Long> {
    public StrictRoutingCounterDeserializer() { super(Long.class); }

    @Override
    public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if (parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) {
            throw MismatchedInputException.from(parser,Long.class,"Routing counters require an integer.");
        }
        return parser.getLongValue();
    }

    @Override
    public Long getNullValue(DeserializationContext context) throws JsonMappingException {
        throw MismatchedInputException.from(context.getParser(),Long.class,"Routing counters must not be null.");
    }
}
