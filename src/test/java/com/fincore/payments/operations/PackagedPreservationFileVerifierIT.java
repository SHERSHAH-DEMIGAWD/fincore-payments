package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.service.ProjectionPreservationVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.fincore.payments.service.ProjectionPreservationVerifier.*;
import static org.assertj.core.api.Assertions.*;

/** Proves the shipped entry point verifies files without starting Spring or infrastructure. */
class PackagedPreservationFileVerifierIT {
    @TempDir Path directory;
    private final ObjectMapper mapper=JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Test
    void validBundleWorksDespiteUnusableDatabaseKafkaAndSpringConfiguration() throws Exception {
        files();
        var result=invoke();
        assertThat(result.exit()).isZero();
        assertThat(result.json().path("status").asText()).isEqualTo("VERIFIED_OFFLINE_NO_DELETION_AUTHORITY");
        assertThat(result.json().path("databaseContacted").asBoolean()).isFalse();
        assertThat(result.json().path("deletionPermitted").asBoolean()).isFalse();
        assertThat(result.stderr()).doesNotContain("Spring Boot","Hikari","Kafka version","Exception");
    }

    @Test
    void changedFileIsRefusedWithSanitizedMachineOutputAndNonzeroExit() throws Exception {
        files();
        Files.writeString(directory.resolve("bundle.json"),Files.readString(directory.resolve("bundle.json"))+" ");
        var result=invoke();
        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.json().path("status").asText()).isEqualTo("PRESERVATION_FILE_VERIFICATION_FAILED");
        assertThat(result.raw()).doesNotContain(directory.toString(),"PRIVATE-PACKAGED","operator","Exception");
    }

    @Test
    void missingArgumentsDoNotFallThroughIntoSpringStartup() throws Exception {
        var result=invoke("--bundle="+directory.resolve("missing-private-file.json"));
        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.json().path("status").asText()).isEqualTo("PRESERVATION_FILE_VERIFICATION_FAILED");
        assertThat(result.raw()+result.stderr()).doesNotContain("missing-private-file","Spring Boot","Hikari","Exception");
    }

    private record Result(int exit,JsonNode json,String raw,String stderr) { }
    private Result invoke(String... explicit) throws Exception {
        String executable=System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var arguments=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Dspring.datasource.url=jdbc:unavailable:offline-must-not-connect",
                "-Dspring.kafka.bootstrap-servers=127.0.0.1:1",
                "-Dfincore.projection-approvals.validity-minutes=0",
                "-jar",Path.of("target/fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"verify-preservation"));
        arguments.addAll(explicit.length==0 ? List.of("--bundle="+directory.resolve("bundle.json"),
                "--metadata="+directory.resolve("metadata.json"),"--plan="+directory.resolve("plan.json")) : List.of(explicit));
        Path out=directory.resolve("stdout-"+UUID.randomUUID()+".json"),err=directory.resolve("stderr-"+UUID.randomUUID()+".txt");
        var process=new ProcessBuilder(arguments).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Offline verifier exceeded 30 seconds."); }
        String raw=Files.readString(out),stderr=Files.readString(err);
        return new Result(process.exitValue(),mapper.readTree(raw),raw,stderr);
    }
    private void files() throws Exception {
        UUID planId=UUID.randomUUID(),preservationId=UUID.randomUUID();
        Instant at=Instant.parse("2026-10-24T12:00:00Z");
        String body="{\"payload\":\"PRIVATE-PACKAGED-\u20b9-\ud83d\ude00\"}";
        var record=new ProjectionPreservation.ExportRecord("RECEIPT",UUID.randomUUID().toString(),sha256(bytes(body)),body);
        var plan=new ProjectionPurgePlan(planId,1,UUID.randomUUID(),"primary","topic",0,3,3,at.minusSeconds(31*86400L),at,30,
                "operator","trusted plan","PLANNED_REVIEW_REQUIRED",false,List.of("RUN_IDENTITIES","MANIFESTS","RECEIPTS","BASELINE"),
                List.of(new ProjectionPurgePlan.Artifact(record.kind(),record.identity(),record.sha256())));
        String bundle=new ProjectionPreservationVerifier(mapper).encode(new ProjectionPreservation.Bundle(1,preservationId,plan,at,
                "operator","preserve",List.of(record)));
        var metadata=new ProjectionPreservation(preservationId,planId,1,3,at,"operator","preserve",1,bytes(bundle).length,
                sha256(bytes(bundle)),"PRESERVED_IN_DATABASE_REVIEW_REQUIRED",false);
        Files.writeString(directory.resolve("bundle.json"),bundle);
        Files.writeString(directory.resolve("metadata.json"),mapper.writeValueAsString(metadata));
        Files.writeString(directory.resolve("plan.json"),mapper.writeValueAsString(plan));
    }
}
