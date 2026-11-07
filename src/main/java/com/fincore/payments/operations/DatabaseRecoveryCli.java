package com.fincore.payments.operations;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.fincore.payments.operations.DatabaseRecovery.*;

/** Explicit headless dispatch: no Spring, server, scheduler, Kafka, Redis or trace exporter. */
public final class DatabaseRecoveryCli {
    private static final int MAX_MANIFEST_BYTES=1024*1024;
    private static final String REFUSAL="{\"status\":\"DATABASE_RECOVERY_REFUSED\",\"deletionPermitted\":false}";
    private DatabaseRecoveryCli() { }
    public static int run(String command,String[] args,PrintStream output){return run(command,args,System.getenv(),output,DatabaseRecovery::dump);}
    static int run(String command,String[] args,Map<String,String> environment,PrintStream output,Dump dump) {
        try {
            var mapper=mapper();var target=Target.from(environment);
            if(command.equals("create-recovery-backup")) {
                var options=options(args,Set.of("directory","pg-dump"));
                Path directory=Path.of(options.get("directory")).toAbsolutePath().normalize(),binary=Path.of(options.get("pg-dump"));
                if(!binary.isAbsolute() || !Files.isRegularFile(binary))throw new IllegalArgumentException();
                Capture result;
                try(var source=target.connect()) {
                    String capturedAt=Instant.now().toString();
                    var inventory=inventory(source);String snapshot=scalar(source,"SELECT pg_export_snapshot()");
                    Files.createDirectory(directory);
                    dump.run(binary,target,snapshot,directory);
                    Path archive=directory.resolve("database.dump");String archiveHash=fileHash(archive,MAX_ARCHIVE_BYTES);
                    var manifest=new Manifest(1,"PUBLIC_SCHEMA_V22",UUID.randomUUID(),capturedAt,target.identityHash(),
                            Files.size(archive),archiveHash,inventory,false);
                    byte[] manifestBytes=mapper.writeValueAsBytes(manifest);
                    Files.write(directory.resolve("manifest.json"),manifestBytes,StandardOpenOption.CREATE_NEW);
                    result=new Capture("BACKUP_CAPTURED_RESTORE_NOT_YET_VERIFIED",manifest.bundleId(),
                            sha256(manifestBytes),archiveHash,inventory.tables().size(),totalRows(inventory),false,false);
                }
                output.println(mapper.writeValueAsString(result));
            } else if(command.equals("verify-recovery-restore")) {
                var options=options(args,Set.of("directory","manifest-sha256","report"));
                Path directory=Path.of(options.get("directory")),report=Path.of(options.get("report"));
                String trustedHash=options.get("manifest-sha256");
                if(!HASH.matcher(trustedHash).matches() || !target.database().matches("fincore_restore_[a-z0-9_]{1,40}"))throw new IllegalArgumentException();
                var manifest=readManifest(directory.resolve("manifest.json"),trustedHash,mapper);
                if(target.identityHash().equals(manifest.sourceIdentitySha256()))throw new IllegalArgumentException();
                Path archive=directory.resolve("database.dump");
                if(!fileHash(archive,MAX_ARCHIVE_BYTES).equals(manifest.archiveSha256()) || Files.size(archive)!=manifest.archiveBytes())throw new IllegalArgumentException();
                Verification verification;
                try(var restored=target.connect()) {
                    var observed=inventory(restored);
                    if(!observed.equals(manifest.inventory()))throw new IllegalArgumentException();
                    verification=new Verification("VERIFIED_ISOLATED_DATABASE_RESTORE_NO_DELETION_AUTHORITY",UUID.randomUUID(),manifest.bundleId(),
                            Instant.now().toString(),trustedHash,manifest.archiveSha256(),target.identityHash(),observed.tables().size(),totalRows(observed),true,false,false);
                }
                byte[] bytes=mapper.writeValueAsBytes(verification);Files.write(report,bytes,StandardOpenOption.CREATE_NEW);
                output.println(new String(bytes,StandardCharsets.UTF_8));
            } else throw new IllegalArgumentException();
            return 0;
        } catch(Exception refused) {
            if(refused instanceof InterruptedException)Thread.currentThread().interrupt();
            output.println(REFUSAL);return 2;
        }
    }

    static ObjectMapper mapper() {
        return JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    }
    static Map<String,String> options(String[] args,Set<String> expected) {
        if(args==null || args.length!=expected.size())throw new IllegalArgumentException();
        var result=new HashMap<String,String>();
        for(String arg:args) {
            if(arg==null || !arg.startsWith("--"))throw new IllegalArgumentException();int separator=arg.indexOf('=');
            if(separator<3 || separator==arg.length()-1)throw new IllegalArgumentException();
            String name=arg.substring(2,separator);
            if(!expected.contains(name) || result.putIfAbsent(name,arg.substring(separator+1))!=null)throw new IllegalArgumentException();
        }
        if(!result.keySet().equals(expected))throw new IllegalArgumentException();return Map.copyOf(result);
    }
    static Manifest readManifest(Path path,String expectedHash,ObjectMapper mapper)throws Exception {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();byte[] bytes;
        try(var input=Files.newInputStream(path)){bytes=input.readNBytes(MAX_MANIFEST_BYTES+1);}
        if(bytes.length==0 || bytes.length>MAX_MANIFEST_BYTES || !sha256(bytes).equals(expectedHash))throw new IllegalArgumentException();
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        var value=mapper.readValue(json,Manifest.class);validate(value);return value;
    }
    static void validate(Manifest m) {
        if(m.formatVersion()!=1 || !m.scope().equals("PUBLIC_SCHEMA_V22") || m.bundleId().equals(new UUID(0,0))
                || m.deletionPermitted() || m.archiveBytes()<1 || m.archiveBytes()>MAX_ARCHIVE_BYTES
                || !HASH.matcher(m.archiveSha256()).matches() || !HASH.matcher(m.sourceIdentitySha256()).matches())throw new IllegalArgumentException();
        Instant.parse(m.capturedAt());Inventory i=m.inventory();
        if((i.postgresMajor()!=17 && i.postgresMajor()!=18) || !HASH.matcher(i.schemaSha256()).matches() || i.tables().isEmpty() || i.tables().size()>128)throw new IllegalArgumentException();
        String prior="";long total=0;
        for(Table table:i.tables()) {
            if(!table.name().matches("[a-z][a-z0-9_]{0,62}") || table.name().compareTo(prior)<=0 || table.rows()<0
                    || !HASH.matcher(table.sha256()).matches() || (total+=table.rows())>MAX_ROWS)throw new IllegalArgumentException();prior=table.name();
        }
    }
    private static long totalRows(Inventory inventory){return inventory.tables().stream().mapToLong(Table::rows).sum();}
}
