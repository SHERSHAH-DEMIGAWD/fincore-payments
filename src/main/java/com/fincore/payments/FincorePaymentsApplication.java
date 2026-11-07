package com.fincore.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude={
    org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class})
public class FincorePaymentsApplication {
    public static void main(String[] args) {
        if(args.length>0 && java.util.Set.of("init-local-sandbox","bootstrap-local-sandbox","probe-readiness").contains(args[0])) {
            System.exit(com.fincore.payments.deployment.LocalSandboxCli.run(args[0],java.util.Arrays.copyOfRange(args,1,args.length),System.out));return;
        }
        if(args.length>0 && args[0].equals("sandbox-acceptance")) {
            System.exit(com.fincore.payments.deployment.SandboxAcceptanceCli.run(java.util.Arrays.copyOfRange(args,1,args.length),System.out));return;
        }
        if (args.length>0 && (args[0].equals("create-recovery-backup") || args[0].equals("verify-recovery-restore"))) {
            System.exit(com.fincore.payments.operations.DatabaseRecoveryCli.run(args[0],
                    java.util.Arrays.copyOfRange(args,1,args.length),System.out));
            return;
        }
        if (args.length>0 && args[0].equals("provider-sandbox")) {
            System.exit(com.fincore.payments.provider.SandboxProviderServer.run(
                    java.util.Arrays.copyOfRange(args,1,args.length),System.out));
            return;
        }
        if (args.length>0 && args[0].equals("verify-preservation")) {
            System.exit(com.fincore.payments.operations.PreservationFileVerifierCli.run(
                    java.util.Arrays.copyOfRange(args,1,args.length),System.out));
            return;
        }
        SpringApplication.run(FincorePaymentsApplication.class, args);
    }
}
