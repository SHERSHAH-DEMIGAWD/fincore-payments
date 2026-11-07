package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class PackagedDatabaseRecoveryIT {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"create-recovery-backup","verify-recovery-restore"})
    void missingArgumentsReturnSanitizedRefusalWithoutStartingSpring(String command)throws Exception {
        String binary=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),"-jar",
                Path.of("target/fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),command);
        builder.environment().putAll(Map.of("FINCORE_RECOVERY_DB_URL","PRIVATE-invalid-url","FINCORE_RISK_ENABLED","true","FINCORE_RISK_REDIS_PORT","invalid",
                "FINCORE_OBSERVABILITY_ENABLED","true","FINCORE_OBSERVABILITY_PASSWORD_HASH","invalid"));
        Path out=directory.resolve(command+".json"),err=directory.resolve(command+".err");
        Process child=builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isEqualTo(2);
            String output=Files.readString(out);assertThat(new ObjectMapper().readTree(output).path("deletionPermitted").asBoolean()).isFalse();
            assertThat(output+Files.readString(err)).doesNotContain("PRIVATE","Spring Boot","Hikari","Exception");
        } finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
}
