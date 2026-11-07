package com.fincore.payments.operations;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class PackagedLocalSandboxToolsIT {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"init-local-sandbox","bootstrap-local-sandbox","probe-readiness","sandbox-acceptance"})
    void malformedInvocationDoesNotStartSpringOrExposeConfiguration(String command)throws Exception {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                "-jar",Path.of("target/fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),command);
        builder.environment().putAll(Map.of("DB_URL","PRIVATE-invalid-url","FINCORE_RISK_ENABLED","true","FINCORE_RISK_REDIS_PORT","invalid",
                "FINCORE_OBSERVABILITY_ENABLED","true","FINCORE_OBSERVABILITY_PASSWORD_HASH","PRIVATE-invalid"));
        Path out=directory.resolve(command+".out"),err=directory.resolve(command+".err");var process=builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try{assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isEqualTo(2);
            assertThat(Files.readString(out)+Files.readString(err)).doesNotContain("PRIVATE","Spring Boot","Hikari","Exception");}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
}
