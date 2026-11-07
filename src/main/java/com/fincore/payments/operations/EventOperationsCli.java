package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.FincorePaymentsApplication;
import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.dto.ConsumerRecoveryCommand;
import com.fincore.payments.dto.RequeueCommand;
import com.fincore.payments.exception.ConsumerRecoveryConflictException;
import com.fincore.payments.exception.ConsumerRecoveryNotFoundException;
import com.fincore.payments.exception.EventOperationConflictException;
import com.fincore.payments.exception.EventOperationNotFoundException;
import com.fincore.payments.exception.ShadowRebuildConflictException;
import com.fincore.payments.exception.ShadowRebuildNotFoundException;
import com.fincore.payments.service.ConsumerFailureService;
import com.fincore.payments.service.ConsumerRecoveryService;
import com.fincore.payments.service.EventOperationsService;
import com.fincore.payments.service.KafkaLagService;
import com.fincore.payments.service.ProjectionReconciliationService;
import com.fincore.payments.service.ProjectionRebuildService;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local operator entry point. It never starts an HTTP server, dispatcher, or Kafka listener. */
public final class EventOperationsCli {
    private static final Map<String, Set<String>> OPTIONS = Map.ofEntries(
            Map.entry("overview", Set.of()), Map.entry("lag", Set.of()),
            Map.entry("events", Set.of("status", "limit")),
            Map.entry("requeues", Set.of("event-id", "limit")),
            Map.entry("failures", Set.of("state", "limit")),
            Map.entry("requeue", Set.of("event-id", "request-id", "expected-generation", "operator", "reason")),
            Map.entry("gaps", Set.of()), Map.entry("quarantines", Set.of("state", "limit")),
            Map.entry("quarantine", Set.of("failure-id", "request-id", "expected-fingerprint", "operator", "reason",
                    "accept-projection-gap")),
            Map.entry("replay", Set.of("failure-id", "request-id", "expected-fingerprint", "operator", "reason")),
            Map.entry("reconcile", Set.of("payment-id", "after-payment-id", "limit")),
            Map.entry("rebuild-create", Set.of("run-id", "operator", "reason")),
            Map.entry("rebuild-step", Set.of("run-id", "limit")),
            Map.entry("rebuild-status", Set.of("run-id")),
            Map.entry("rebuild-validate", Set.of("run-id")),
            Map.entry("rebuild-catch-up", Set.of("run-id", "expected-generation", "request-id", "operator", "reason")));

    private EventOperationsCli() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream errors) {
        try {
            Invocation command = parse(args);
            if (command.name().equals("help")) {
                output.println(usage());
                return 0;
            }
            // Command-line properties override environment defaults, including a running API's
            // scheduling settings. No caller-supplied Spring flags pass through this parser.
            try (var context = new SpringApplicationBuilder(FincorePaymentsApplication.class)
                    .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                    .run("--spring.main.web-application-type=none", "--fincore.outbox.enabled=false",
                            "--fincore.provider.enabled=false",
                            "--fincore.provider-webhook.enabled=false",
                            "--fincore.merchant-webhooks.enabled=false", "--fincore.merchant-webhooks.dispatch-enabled=false",
                            "--fincore.settlement.enabled=false",
                            "--fincore.risk.enabled=false",
                            "--fincore.observability.enabled=false",
                            "--fincore.observability.trace-export-enabled=false",
                            "--management.tracing.enabled=false",
                            "--fincore.kafka.listener-auto-startup=false", "--spring.kafka.admin.auto-create=false",
                            "--logging.config=classpath:logback-operations.xml")) {
                Object result = execute(command, context.getBean(EventOperationsService.class),
                        context.getBean(KafkaLagService.class), context.getBean(ConsumerFailureService.class),
                        context.getBean(ConsumerRecoveryService.class), context.getBean(ProjectionReconciliationService.class),
                        context.getBean(ProjectionRebuildService.class));
                output.println(context.getBean(ObjectMapper.class).writeValueAsString(result));
                return 0;
            }
        } catch (IllegalArgumentException exception) {
            return error(errors, 2, "INVALID_ARGUMENTS", exception.getMessage());
        } catch (EventOperationConflictException exception) {
            return error(errors, 3, "RECOVERY_CONFLICT", "Recovery preconditions or request identity conflict.");
        } catch (ConsumerRecoveryConflictException exception) {
            return error(errors, 3, "RECOVERY_CONFLICT", "Recovery preconditions or request identity conflict.");
        } catch (ShadowRebuildConflictException exception) {
            return error(errors, 3, "REBUILD_CONFLICT", "Rebuild preconditions or command identity conflict.");
        } catch (EventOperationNotFoundException exception) {
            return error(errors, 4, "EVENT_NOT_FOUND", "The outbox event does not exist.");
        } catch (ConsumerRecoveryNotFoundException exception) {
            return error(errors, 4, "FAILURE_NOT_FOUND", "The consumer failure does not exist in the configured scope.");
        } catch (ShadowRebuildNotFoundException exception) {
            return error(errors, 4, "REBUILD_NOT_FOUND", "The shadow rebuild does not exist in the configured scope.");
        } catch (Exception exception) {
            return error(errors, 5, "OPERATIONS_FAILED", "Operation failed; inspect local diagnostics.");
        }
    }

    static Object execute(Invocation command, EventOperationsService operations, KafkaLagService lag,
                          ConsumerFailureService failures, ConsumerRecoveryService recovery,
                          ProjectionReconciliationService reconciliation, ProjectionRebuildService rebuild) {
        var options = command.options();
        int limit = limit(options);
        return switch (command.name()) {
            case "overview" -> operations.overview();
            case "events" -> operations.events(status(options), limit);
            case "lag" -> lag.report();
            case "failures" -> failures.list(options.getOrDefault("state", "OPEN").equals("OPEN"), limit);
            case "requeues" -> operations.requeues(uuid(options, "event-id"), limit);
            case "requeue" -> operations.requeue(requeueCommand(options));
            case "gaps" -> recovery.gaps();
            case "quarantines" -> recovery.quarantines(options.getOrDefault("state", "OPEN").equals("OPEN"), limit);
            case "quarantine" -> recovery.quarantine(consumerRecoveryCommand(options));
            case "replay" -> recovery.replay(consumerRecoveryCommand(options));
            case "reconcile" -> reconciliation.reconcile(optionalUuid(options, "payment-id"),
                    optionalUuid(options, "after-payment-id"), limit);
            case "rebuild-create" -> rebuild.create(uuid(options, "run-id"), text(options, "operator", 100), text(options, "reason", 500));
            case "rebuild-step" -> rebuild.step(uuid(options, "run-id"), limit);
            case "rebuild-status" -> rebuild.status(uuid(options, "run-id"));
            case "rebuild-validate" -> rebuild.validate(uuid(options, "run-id"));
            case "rebuild-catch-up" -> rebuild.catchUp(uuid(options, "run-id"), expectedGeneration(options),
                    uuid(options, "request-id"), text(options, "operator", 100), text(options, "reason", 500));
            default -> throw new IllegalArgumentException("Unknown operations command.");
        };
    }

    static Invocation parse(String[] args) {
        if (args.length == 0 || (args.length == 1 && (args[0].equals("help") || args[0].equals("--help")))) {
            return new Invocation("help", Map.of());
        }
        String name = args[0];
        if (!OPTIONS.containsKey(name)) throw new IllegalArgumentException("Unknown operations command.");
        Map<String, String> options = new HashMap<>();
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            int separator = argument.indexOf('=');
            if (!argument.startsWith("--") || separator < 3) {
                throw new IllegalArgumentException("Options must use --name=value syntax.");
            }
            String option = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (!OPTIONS.get(name).contains(option) || value.isEmpty() || options.putIfAbsent(option, value) != null) {
                throw new IllegalArgumentException("Unknown, empty, or duplicate command option.");
            }
        }
        // Validate everything before opening the application or contacting the database.
        limit(options);
        switch (name) {
            case "events" -> status(options);
            case "failures", "quarantines" -> {
                if (!Set.of("OPEN", "ALL").contains(options.getOrDefault("state", "OPEN"))) {
                    throw new IllegalArgumentException("State must be OPEN or ALL.");
                }
            }
            case "requeues" -> uuid(options, "event-id");
            case "requeue" -> requeueCommand(options);
            case "quarantine" -> {
                if (!required(options, "accept-projection-gap").equals("true")) {
                    throw new IllegalArgumentException("Quarantine requires --accept-projection-gap=true.");
                }
                consumerRecoveryCommand(options);
            }
            case "replay" -> consumerRecoveryCommand(options);
            case "reconcile" -> {
                UUID paymentFilter = optionalUuid(options, "payment-id");
                UUID after = optionalUuid(options, "after-payment-id");
                if (paymentFilter != null && after != null) {
                    throw new IllegalArgumentException("Payment filter and pagination cursor cannot be combined.");
                }
            }
            case "rebuild-create" -> {
                uuid(options, "run-id");
                text(options, "operator", 100);
                text(options, "reason", 500);
            }
            case "rebuild-step", "rebuild-status", "rebuild-validate" -> uuid(options, "run-id");
            case "rebuild-catch-up" -> {
                uuid(options, "run-id");
                expectedGeneration(options);
                uuid(options, "request-id");
                text(options, "operator", 100);
                text(options, "reason", 500);
            }
            default -> { }
        }
        return new Invocation(name, Map.copyOf(options));
    }

    private static RequeueCommand requeueCommand(Map<String, String> options) {
        long generation = expectedGeneration(options);
        String operator = text(options, "operator", 100);
        String reason = text(options, "reason", 500);
        return new RequeueCommand(uuid(options, "request-id"), uuid(options, "event-id"), generation, operator, reason);
    }

    private static long expectedGeneration(Map<String, String> options) {
        long generation;
        try {
            generation = Long.parseLong(required(options, "expected-generation"));
            if (generation < 0 || generation == Long.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected generation must be a nonnegative incrementable integer.");
        }
        return generation;
    }

    private static ConsumerRecoveryCommand consumerRecoveryCommand(Map<String, String> options) {
        String fingerprint = required(options, "expected-fingerprint");
        if (!fingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Expected fingerprint must be a 64-character lowercase SHA-256 value.");
        }
        return new ConsumerRecoveryCommand(uuid(options, "request-id"), uuid(options, "failure-id"),
                fingerprint, text(options, "operator", 100), text(options, "reason", 500));
    }

    private static String text(Map<String, String> options, String field, int maximum) {
        String value = required(options, field);
        if (value.isBlank() || value.length() > maximum || value.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.LINE_SEPARATOR
                        || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("Operator and reason must be bounded nonblank text without control characters.");
        }
        return value;
    }

    private static String required(Map<String, String> options, String field) {
        String value = options.get(field);
        if (value == null) throw new IllegalArgumentException("Missing required option: " + field + ".");
        return value;
    }

    private static UUID uuid(Map<String, String> options, String field) {
        String value = required(options, field);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("IDs must be canonical UUIDs.");
        }
    }

    private static UUID optionalUuid(Map<String, String> options, String field) {
        return options.containsKey(field) ? uuid(options, field) : null;
    }

    private static OutboxStatus status(Map<String, String> options) {
        String value = options.getOrDefault("status", "DEAD");
        if (value.equals("ALL")) return null;
        try {
            return OutboxStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Status must be PENDING, DELIVERED, DEAD, or ALL.");
        }
    }

    private static int limit(Map<String, String> options) {
        try {
            int value = Integer.parseInt(options.getOrDefault("limit", "50"));
            if (value < 1 || value > 100) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Limit must be between 1 and 100.");
        }
    }

    private static int error(PrintStream output, int exitCode, String code, String message) {
        try {
            output.println(new ObjectMapper().writeValueAsString(Map.of("error", code, "message", message)));
        } catch (Exception ignored) {
            output.println("{\"error\":\"OPERATIONS_FAILED\"}");
        }
        return exitCode;
    }

    static String usage() {
        return """
                Local event operations (JSON results; diagnostics on stderr)
                  overview
                  events [--status=DEAD|PENDING|DELIVERED|ALL] [--limit=50]
                  lag
                  failures [--state=OPEN|ALL] [--limit=50]
                  gaps
                  reconcile [--payment-id=UUID | --after-payment-id=UUID] [--limit=50]
                  rebuild-create --run-id=UUID --operator=NAME --reason=TEXT
                  rebuild-step --run-id=UUID [--limit=50]
                  rebuild-status --run-id=UUID
                  rebuild-validate --run-id=UUID
                  rebuild-catch-up --run-id=UUID --expected-generation=N --request-id=UUID --operator=NAME --reason=TEXT
                  quarantines [--state=OPEN|ALL] [--limit=50]
                  requeues --event-id=UUID [--limit=50]
                  requeue --event-id=UUID --expected-generation=N --request-id=UUID --operator=NAME --reason=TEXT
                  quarantine --failure-id=UUID --expected-fingerprint=SHA256 --request-id=UUID --operator=NAME --reason=TEXT --accept-projection-gap=true
                  replay --failure-id=UUID --expected-fingerprint=SHA256 --request-id=UUID --operator=NAME --reason=TEXT
                Recovery requires local access to the configured database. Reuse request-id only for the identical command.
                Quarantine accepts a visible projection gap. Replay validates the preserved original record; it never replaces its payload.
                Rebuild commands operate on an isolated shadow identity. They never promote or replace the live projection.
                """;
    }

    record Invocation(String name, Map<String, String> options) { }
}
