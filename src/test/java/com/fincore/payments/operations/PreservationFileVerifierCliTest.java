package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.dto.ProjectionPreservation.*;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.service.ProjectionPreservationVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.fincore.payments.service.ProjectionPreservationVerifier.*;
import static org.assertj.core.api.Assertions.*;

class PreservationFileVerifierCliTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private Path bundleFile,metadataFile,planFile;
    private ProjectionPreservation metadata;
    private ProjectionPurgePlan plan;

    @BeforeEach
    void fixture() throws Exception {
        bundleFile=directory.resolve("private bundle=copy.json");
        metadataFile=directory.resolve("metadata.json"); planFile=directory.resolve("plan.json");
        Instant observed=Instant.parse("2026-10-24T12:00:00.123456Z");
        String body="{\"payload\":\"private-\u20b9-\ud83d\ude00\",\"amount\":10.00}";
        var row=new ExportRecord("RECEIPT",UUID.randomUUID().toString(),sha256(bytes(body)),body);
        plan=new ProjectionPurgePlan(UUID.randomUUID(),1,UUID.randomUUID(),"primary","payment-events.v1",0,3,3,
                observed.minusSeconds(31*86400),observed,30,"private-operator","private-plan-reason", "PLANNED_REVIEW_REQUIRED",false,
                List.of("KEEP_RUN_IDENTITIES_AND_ROUTING_DECISIONS","PRESERVE_FROZEN_MANIFESTS_AND_CHECKPOINTS_BEFORE_ANY_FUTURE_PURGE",
                        "PRESERVE_TARGET_RECEIPTS_AND_PROJECTIONS_BEFORE_ANY_FUTURE_PURGE","KEEP_BASELINE_SOURCE_LEDGER_AND_RECOVERY_EVIDENCE_OUT_OF_SCOPE"),
                List.of(new ProjectionPurgePlan.Artifact(row.kind(),row.identity(),row.sha256())));
        UUID id=UUID.randomUUID();
        String json=new ProjectionPreservationVerifier(mapper).encode(new Bundle(1,id,plan,observed,"private-operator","private-export-reason",List.of(row)));
        metadata=new ProjectionPreservation(id,plan.requestId(),1,3,observed,"private-operator","private-export-reason",1,
                bytes(json).length,sha256(bytes(json)),"PRESERVED_IN_DATABASE_REVIEW_REQUIRED",false);
        Files.writeString(bundleFile,json,StandardCharsets.UTF_8);
        Files.writeString(metadataFile,mapper.writeValueAsString(metadata),StandardCharsets.UTF_8);
        Files.writeString(planFile,mapper.writeValueAsString(plan),StandardCharsets.UTF_8);
    }

    @Test
    void verifiesRealGeneratedUnicodeBundleWithoutAContextAndReportsOnlySafeMetadata() throws Exception {
        var result=run(options());
        assertThat(result.code()).isZero();
        var report=mapper.readTree(result.output());
        assertThat(report.get("status").asText()).isEqualTo("VERIFIED_OFFLINE_NO_DELETION_AUTHORITY");
        assertThat(report.get("planId").asText()).isEqualTo(plan.requestId().toString());
        assertThat(report.get("preservationId").asText()).isEqualTo(metadata.requestId().toString());
        assertThat(report.get("sha256").asText()).isEqualTo(metadata.sha256());
        assertThat(report.get("exportBytes").asLong()).isEqualTo(Files.size(bundleFile));
        assertThat(report.get("artifactCount").asInt()).isEqualTo(1);
        assertThat(report.get("databaseContacted").asBoolean()).isFalse();
        assertThat(report.get("deletionPermitted").asBoolean()).isFalse();
        assertThat(result.output()).doesNotContain("private-",directory.toString(),"Spring", "Hibernate");
    }

    @ParameterizedTest @ValueSource(strings={"changed","truncated","newline"})
    void exactSavedFileDigestDetectsAlteredBytes(String mutation) throws Exception {
        String original=Files.readString(bundleFile);
        String changed=switch (mutation) {
            case "changed" -> original.replace("private-operator","forged-operator");
            case "truncated" -> original.substring(0,original.length()-1);
            default -> original+"\n";
        };
        Files.writeString(bundleFile,changed,StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @Test
    void recomputingFileDigestCannotHideAlteredRecordBody() throws Exception {
        var json=(ObjectNode) mapper.readTree(Files.readString(bundleFile));
        ((ObjectNode) json.withArray("records").get(0)).put("body","{\"payload\":\"forged\"}");
        saveBundleAndRefreshDigest(mapper.writeValueAsString(json));
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"metadata","plan"})
    void refusesWrongIndependentlyRetainedReferences(String reference) throws Exception {
        Path path=reference.equals("metadata") ? metadataFile : planFile;
        var tree=(ObjectNode) mapper.readTree(Files.readString(path));
        tree.put("requestId",UUID.randomUUID().toString());
        Files.writeString(path,mapper.writeValueAsString(tree),StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void duplicateKeysAreRejectedEvenWhenTheirValuesAgree(String target) throws Exception {
        Path path=path(target);
        String original=Files.readString(path);
        String key=target.equals("plan") ? "planVersion" : "formatVersion";
        String json="{\""+key+"\":1,"+original.substring(1);
        if (target.equals("bundle")) saveBundleAndRefreshDigest(json);
        else Files.writeString(path,json,StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void trailingJsonTokensAreRejected(String target) throws Exception {
        Path path=path(target); String json=Files.readString(path)+" {}";
        if (target.equals("bundle")) saveBundleAndRefreshDigest(json);
        else Files.writeString(path,json,StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void unknownVersionOneFieldsAreRejected(String target) throws Exception {
        Path path=path(target);
        var tree=(ObjectNode) mapper.readTree(Files.readString(path)); tree.put("unknown",true);
        String json=mapper.writeValueAsString(tree);
        if (target.equals("bundle")) saveBundleAndRefreshDigest(json);
        else Files.writeString(path,json,StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void onlyVersionOneIsSupported(String target) throws Exception {
        Path path=path(target);
        var tree=(ObjectNode) mapper.readTree(Files.readString(path)); tree.put(target.equals("plan") ? "planVersion" : "formatVersion",2);
        String json=mapper.writeValueAsString(tree);
        if (target.equals("bundle")) saveBundleAndRefreshDigest(json);
        else Files.writeString(path,json,StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"\"1\"","1.0","1e0","null","missing"})
    void primitiveCountsCannotBeCoercedOrDefaulted(String value) throws Exception {
        String json=Files.readString(metadataFile);
        json=value.equals("missing") ? json.replace("\"artifactCount\":1,","")
                : json.replace("\"artifactCount\":1","\"artifactCount\":"+value);
        Files.writeString(metadataFile,json,StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @Test
    void timestampsMustUseTheVersionOneStringRepresentation() throws Exception {
        var tree=(ObjectNode) mapper.readTree(Files.readString(metadataFile)); tree.put("preservedAt",1792843200);
        Files.writeString(metadataFile,mapper.writeValueAsString(tree),StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void malformedUtf8IsRejectedWithoutReplacementCharacters(String target) throws Exception {
        Files.write(path(target),new byte[]{(byte) 0xc3,(byte) 0x28});
        assertRefused(run(options()));
    }

    @Test
    void otherAutoDetectedJsonEncodingsAreNotAcceptedAsUtf8() throws Exception {
        Files.write(metadataFile,Files.readString(metadataFile).getBytes(StandardCharsets.UTF_16BE));
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void boundedReadsRefuseOversizedInputs(String target) throws Exception {
        int maximum=target.equals("bundle") ? MAX_EXPORT_BYTES : PreservationFileVerifierCli.MAX_REFERENCE_BYTES;
        Files.writeString(path(target),Files.readString(path(target))+" ".repeat(maximum+1),StandardCharsets.UTF_8);
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"bundle","metadata","plan"})
    void emptyOrMissingFilesDoNotLeakPaths(String target) throws Exception {
        Files.write(path(target),new byte[0]);
        assertRefused(run(options()));
        Files.delete(path(target));
        assertRefused(run(options()));
    }

    @ParameterizedTest @ValueSource(strings={"missing","duplicate","unknown","blank","positional","extra"})
    void allThreeUniqueNamedOptionsAreRequired(String mutation) {
        String[] args=switch (mutation) {
            case "missing" -> new String[]{"--bundle="+bundleFile,"--metadata="+metadataFile};
            case "duplicate" -> new String[]{"--bundle="+bundleFile,"--bundle="+bundleFile,"--plan="+planFile};
            case "unknown" -> new String[]{"--url=https://example.invalid/","--metadata="+metadataFile,"--plan="+planFile};
            case "blank" -> new String[]{"--bundle=","--metadata="+metadataFile,"--plan="+planFile};
            case "positional" -> new String[]{bundleFile.toString(),"--metadata="+metadataFile,"--plan="+planFile};
            default -> new String[]{"--bundle="+bundleFile,"--metadata="+metadataFile,"--plan="+planFile,"--help"};
        };
        assertRefused(run(args));
    }

    private String[] options() { return new String[]{"--bundle="+bundleFile,"--metadata="+metadataFile,"--plan="+planFile}; }
    private Path path(String target) { return target.equals("bundle") ? bundleFile : target.equals("metadata") ? metadataFile : planFile; }
    private void saveBundleAndRefreshDigest(String json) throws Exception {
        Files.writeString(bundleFile,json,StandardCharsets.UTF_8);
        var tree=(ObjectNode) mapper.readTree(Files.readString(metadataFile));
        tree.put("exportBytes",bytes(json).length); tree.put("sha256",sha256(bytes(json)));
        Files.writeString(metadataFile,mapper.writeValueAsString(tree),StandardCharsets.UTF_8);
    }
    private Outcome run(String[] args) {
        var output=new ByteArrayOutputStream();
        int code=PreservationFileVerifierCli.run(args,new PrintStream(output,true,StandardCharsets.UTF_8));
        return new Outcome(code,output.toString(StandardCharsets.UTF_8));
    }
    private void assertRefused(Outcome outcome) {
        assertThat(outcome.code()).isEqualTo(2);
        assertThat(outcome.output()).isEqualTo("{\"status\":\"PRESERVATION_FILE_VERIFICATION_FAILED\",\"message\":\"Unable to verify supplied files and trusted references.\",\"deletionPermitted\":false}"+System.lineSeparator());
        assertThat(outcome.output()).doesNotContain(directory.toString(),"private-", "Exception", "Spring", "Hibernate");
    }
    private record Outcome(int code,String output) { }
}
