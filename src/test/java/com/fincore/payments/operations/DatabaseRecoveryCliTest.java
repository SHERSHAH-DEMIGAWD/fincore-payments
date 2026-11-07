package com.fincore.payments.operations;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.fincore.payments.operations.DatabaseRecovery.*;
import static org.assertj.core.api.Assertions.*;

class DatabaseRecoveryCliTest {
    @TempDir Path directory;
    static final String HASH="a".repeat(64);
    static final Map<String,String> ENV=Map.of("FINCORE_RECOVERY_DB_URL","jdbc:postgresql://127.0.0.1:55432/fincore_source",
            "FINCORE_RECOVERY_DB_USERNAME","backup_user","FINCORE_RECOVERY_DB_PASSWORD","PRIVATE-PASSWORD");

    @Test void settingsContainNoCredentialsInDiagnosticRepresentation() {
        Target t=Target.from(ENV);assertThat(t.port()).isEqualTo(55432);assertThat(t.database()).isEqualTo("fincore_source");
        assertThat(t.toString()).doesNotContain("PRIVATE","backup_user","55432","fincore_source");
        assertThat(t.identityHash()).matches("[0-9a-f]{64}");
    }
    @ParameterizedTest @ValueSource(strings={"jdbc:postgresql://remote:5432/payments","jdbc:postgresql://localhost:5432/payments",
            "jdbc:postgresql://127.0.0.1:0/payments","jdbc:postgresql://127.0.0.1:65536/payments",
            "jdbc:postgresql://127.0.0.1:5432/payments?user=admin","jdbc:postgresql://127.0.0.1:5432/postgres;drop"})
    void unsupportedConnectionsAreRefused(String url) {
        var env=new HashMap<>(ENV);env.put("FINCORE_RECOVERY_DB_URL",url);
        assertThatThrownBy(()->Target.from(env)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictOptionsRejectDuplicatesUnknownAndMissing() {
        for(String[] options:List.of(new String[]{"--directory=x","--directory=y"},new String[]{"--directory=x","--repair=y"},new String[]{"--directory="}))
            assertThatThrownBy(()->DatabaseRecoveryCli.options(options,Set.of("directory","pg-dump"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void manifestRequiresExactTrustedBytesAndStrictJson()throws Exception {
        Path file=directory.resolve("manifest.json");byte[] bytes=DatabaseRecoveryCli.mapper().writeValueAsBytes(manifest());Files.write(file,bytes);
        assertThat(DatabaseRecoveryCli.readManifest(file,sha256(bytes),DatabaseRecoveryCli.mapper())).isEqualTo(manifest());
        Files.writeString(file,new String(bytes,StandardCharsets.UTF_8)+" ");
        assertThatThrownBy(()->DatabaseRecoveryCli.readManifest(file,sha256(bytes),DatabaseRecoveryCli.mapper())).isInstanceOf(IllegalArgumentException.class);
        String duplicate=new String(bytes,StandardCharsets.UTF_8).replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1");
        Files.writeString(file,duplicate);assertThatThrownBy(()->DatabaseRecoveryCli.readManifest(file,sha256(duplicate.getBytes(StandardCharsets.UTF_8)),DatabaseRecoveryCli.mapper())).isInstanceOf(IOException.class);
    }
    @Test void oversizedMalformedAndUnknownManifestFieldsAreRefused()throws Exception {
        Path file=directory.resolve("manifest.json");byte[] tooLarge=new byte[1024*1024+1];Files.write(file,tooLarge);
        assertThatThrownBy(()->DatabaseRecoveryCli.readManifest(file,sha256(tooLarge),DatabaseRecoveryCli.mapper())).isInstanceOf(IllegalArgumentException.class);
        Files.write(file,new byte[]{(byte)0xff});assertThatThrownBy(()->DatabaseRecoveryCli.readManifest(file,sha256(new byte[]{(byte)0xff}),DatabaseRecoveryCli.mapper())).isInstanceOf(java.nio.charset.CharacterCodingException.class);
        byte[] unknown="{\"unknown\":true}".getBytes(StandardCharsets.UTF_8);Files.write(file,unknown);
        assertThatThrownBy(()->DatabaseRecoveryCli.readManifest(file,sha256(unknown),DatabaseRecoveryCli.mapper())).isInstanceOf(IOException.class);
    }
    @Test void manifestRejectsDeletionAuthorityAndUnorderedTables() {
        Manifest m=manifest();
        assertThatThrownBy(()->DatabaseRecoveryCli.validate(new Manifest(1,m.scope(),m.bundleId(),m.capturedAt(),HASH,1,HASH,m.inventory(),true))).isInstanceOf(IllegalArgumentException.class);
        var unordered=new Inventory(18,HASH,List.of(new Table("z",0,HASH),new Table("a",0,HASH)));
        assertThatThrownBy(()->DatabaseRecoveryCli.validate(new Manifest(1,m.scope(),m.bundleId(),m.capturedAt(),HASH,1,HASH,unordered,false))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusalIsSanitizedAndDoesNotFallThroughToInfrastructure() {
        var out=new ByteArrayOutputStream();
        int exit=DatabaseRecoveryCli.run("verify-recovery-restore",new String[]{"--directory=PRIVATE-PATH","--manifest-sha256="+HASH,"--report=PRIVATE-REPORT"},ENV,new PrintStream(out),
                (binary,source,snapshot,path)->{throw new AssertionError("must not dump during verification");});
        assertThat(exit).isEqualTo(2);assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{\"status\":\"DATABASE_RECOVERY_REFUSED\",\"deletionPermitted\":false}"+System.lineSeparator());
    }
    @Test void archiveHashStreamsAndEnforcesSizeLimit()throws Exception {
        Path file=directory.resolve("archive");Files.writeString(file,"abc");
        assertThat(fileHash(file,3)).isEqualTo(sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(()->fileHash(file,2)).isInstanceOf(IllegalArgumentException.class);
    }
    private Manifest manifest(){return new Manifest(1,"PUBLIC_SCHEMA_V22",UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "2026-10-27T12:00:00Z",HASH,1,HASH,new Inventory(18,HASH,List.of(new Table("payments",1,HASH))),false);}
}
