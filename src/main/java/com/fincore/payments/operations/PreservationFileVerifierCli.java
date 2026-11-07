package com.fincore.payments.operations;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.repository.ProjectionPreservationRepository.Stored;
import com.fincore.payments.service.ProjectionPreservationVerifier;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Independent file verification. This class creates no Spring context or network client. */
public final class PreservationFileVerifierCli {
    public static final int MAX_REFERENCE_BYTES=1024*1024;
    private static final String FAILURE="{\"status\":\"PRESERVATION_FILE_VERIFICATION_FAILED\","
            +"\"message\":\"Unable to verify supplied files and trusted references.\",\"deletionPermitted\":false}";

    private PreservationFileVerifierCli() { }

    /** Options only: --bundle=PATH --metadata=PATH --plan=PATH. Return 0 for verification, 2 for refusal. */
    public static int run(String[] args,PrintStream output) {
        try {
            var options=options(args);
            var mapper=mapper();
            String bundleJson=readUtf8(options.get("bundle"),ProjectionPreservationVerifier.MAX_EXPORT_BYTES);
            var metadata=mapper.readValue(readUtf8(options.get("metadata"),MAX_REFERENCE_BYTES),ProjectionPreservation.class);
            var plan=mapper.readValue(readUtf8(options.get("plan"),MAX_REFERENCE_BYTES),ProjectionPurgePlan.class);
            validateReferences(metadata,plan);
            // Parse decoded UTF-8 too: the byte-array reader must not auto-detect a different JSON encoding.
            mapper.readValue(bundleJson,ProjectionPreservation.Bundle.class);
            new ProjectionPreservationVerifier(mapper).verify(new Stored(metadata,bundleJson),plan);
            output.println(mapper.writeValueAsString(new Report("VERIFIED_OFFLINE_NO_DELETION_AUTHORITY",
                    plan.requestId(),metadata.requestId(),metadata.sha256(),metadata.exportBytes(),metadata.artifactCount(),false,false)));
            return 0;
        } catch (IOException | RuntimeException refused) {
            // Do not print file paths, sensitive record bodies, actor/reason, or exception diagnostics.
            output.println(FAILURE);
            return 2;
        }
    }

    private static ObjectMapper mapper() {
        var time=new JavaTimeModule();
        time.addDeserializer(Instant.class,new JsonDeserializer<>() {
            @Override public Instant deserialize(JsonParser parser,DeserializationContext context) throws IOException {
                if (parser.currentToken()!=JsonToken.VALUE_STRING)
                    return (Instant) context.handleUnexpectedToken(Instant.class,parser);
                try { return Instant.parse(parser.getText()); }
                catch (DateTimeParseException invalid) {
                    throw context.weirdStringException("",Instant.class,"Instant must be an ISO-8601 string.");
                }
            }
        });
        return JsonMapper.builder().addModule(time)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }

    private static Map<String,Path> options(String[] args) {
        if (args==null || args.length!=3) throw new IllegalArgumentException();
        var options=new HashMap<String,Path>();
        for (String argument:args) {
            if (argument==null || !argument.startsWith("--")) throw new IllegalArgumentException();
            int separator=argument.indexOf('=');
            if (separator<3 || separator==argument.length()-1) throw new IllegalArgumentException();
            String name=argument.substring(2,separator);
            if (!name.equals("bundle") && !name.equals("metadata") && !name.equals("plan")) throw new IllegalArgumentException();
            if (options.putIfAbsent(name,Path.of(argument.substring(separator+1)))!=null) throw new IllegalArgumentException();
        }
        if (options.size()!=3) throw new IllegalArgumentException();
        return Map.copyOf(options);
    }

    private static String readUtf8(Path path,int maximum) throws IOException {
        byte[] bytes;
        try (var input=Files.newInputStream(path)) { bytes=input.readNBytes(maximum+1); }
        if (bytes.length==0 || bytes.length>maximum) throw new IllegalArgumentException();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void validateReferences(ProjectionPreservation metadata,ProjectionPurgePlan plan) {
        if (metadata.formatVersion()!=1 || plan.planVersion()!=1 || metadata.deletionPermitted() || plan.deletionPermitted()
                || metadata.artifactCount()<1 || metadata.artifactCount()>1000
                || metadata.exportBytes()<1 || metadata.exportBytes()>ProjectionPreservationVerifier.MAX_EXPORT_BYTES
                || metadata.sha256()==null || !metadata.sha256().matches("[0-9a-f]{64}")
                || plan.artifacts()==null || plan.artifacts().isEmpty() || plan.artifacts().size()>1000)
            throw new IllegalArgumentException();
    }

    private record Report(String status,UUID planId,UUID preservationId,String sha256,long exportBytes,
            int artifactCount,boolean databaseContacted,boolean deletionPermitted) { }
}
