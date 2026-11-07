package com.fincore.payments.operations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded, local logical recovery evidence. Never restores SQL or starts application workers. */
public final class DatabaseRecovery {
    public static final long MAX_ARCHIVE_BYTES=512L*1024*1024;
    static final long MAX_ROWS=1_000_000, MAX_DATA_BYTES=256L*1024*1024;
    static final Pattern HASH=Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NAME=Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private DatabaseRecovery() { }

    public record Target(String url,String username,String password,int port,String database) {
        public static Target from(Map<String,String> environment) {
            String url=environment.getOrDefault("FINCORE_RECOVERY_DB_URL","");
            var match=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/([a-z][a-z0-9_]{0,62})").matcher(url);
            String username=environment.getOrDefault("FINCORE_RECOVERY_DB_USERNAME","");
            if(!match.matches() || !NAME.matcher(username).matches())throw new IllegalArgumentException();
            int port=Integer.parseInt(match.group(1));
            if(port<1 || port>65535)throw new IllegalArgumentException();
            return new Target(url,username,environment.getOrDefault("FINCORE_RECOVERY_DB_PASSWORD",""),port,match.group(2));
        }
        public String identityHash(){return sha256(url.getBytes(StandardCharsets.UTF_8));}
        @Override public String toString(){return "RecoveryTarget[REDACTED]";}
        Connection connect()throws SQLException {
            var properties=new Properties();properties.setProperty("user",username);properties.setProperty("password",password);
            properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","60");
            properties.setProperty("ApplicationName","fincore-recovery-read-only");
            Connection connection=DriverManager.getConnection(url,properties);
            try {
                connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
                try(var statement=connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout='30s'");statement.execute("SET LOCAL lock_timeout='5s'");
                    statement.execute("SET LOCAL search_path=pg_catalog,public");statement.execute("SET LOCAL timezone='UTC'");
                    statement.execute("SET LOCAL datestyle='ISO, YMD'");statement.execute("SET LOCAL intervalstyle='iso_8601'");
                    statement.execute("SET LOCAL extra_float_digits=3");statement.execute("SET LOCAL bytea_output='hex'");
                }
                return connection;
            } catch(SQLException|RuntimeException error){connection.close();throw error;}
        }
    }

    public record Table(String name,long rows,String sha256) { }
    public record Inventory(int postgresMajor,String schemaSha256,List<Table> tables) { }
    public record Manifest(int formatVersion,String scope,UUID bundleId,String capturedAt,String sourceIdentitySha256,
                           long archiveBytes,String archiveSha256,Inventory inventory,boolean deletionPermitted) { }
    public record Capture(String status,UUID bundleId,String manifestSha256,String archiveSha256,int tables,long rows,
                          boolean restoreVerified,boolean deletionPermitted) { }
    public record Verification(String status,UUID evidenceId,UUID bundleId,String verifiedAt,String manifestSha256,
                               String archiveSha256,String restoredIdentitySha256,int tables,long rows,boolean restoredDatabaseReadOnly,
                               boolean externalResourcesRecovered,boolean deletionPermitted) { }

    public static Inventory inventory(Connection connection)throws SQLException {
        if(!connection.isReadOnly() || connection.getAutoCommit() || connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)
            throw new IllegalArgumentException();
        int major=Integer.parseInt(scalar(connection,"SHOW server_version_num"))/10000;
        if((major!=17 && major!=18) || !scalar(connection,"SHOW server_encoding").equals("UTF8"))throw new IllegalArgumentException();
        // This version deliberately supports the project's ordinary public tables, functions and indexes only.
        if(!scalar(connection,"SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','information_schema')").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind NOT IN ('r','i')").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_largeobject_metadata").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND (relrowsecurity OR relforcerowsecurity OR relpersistence<>'p')").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_type WHERE typnamespace='public'::regnamespace AND typtype<>'c' AND NOT(typtype='b' AND typelem<>0)").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_proc WHERE pronamespace='public'::regnamespace AND prokind='a'").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_extension WHERE extname<>'plpgsql'").equals("0"))throw new IllegalArgumentException();
        if(!scalar(connection,"SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace AND NOT convalidated").equals("0")
            || !scalar(connection,"SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace='public'::regnamespace AND t.tgenabled<>'O'").equals("0"))throw new IllegalArgumentException();
        var names=new ArrayList<String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename COLLATE \"C\"")) {
            while(rows.next()){String name=rows.getString(1);if(!NAME.matcher(name).matches() || names.size()>=128)throw new IllegalArgumentException();names.add(name);}
        }
        if(!names.containsAll(List.of("payments","idempotency_records","ledger_transactions","ledger_entries","payment_outbox","flyway_schema_history","payment_risk_decisions")))throw new IllegalArgumentException();
        // Keep table DDL out while the exported snapshot and pg_dump share their row image.
        try(var statement=connection.createStatement()) {statement.execute("LOCK TABLE "+String.join(",",names.stream().map(n->"public.\""+n+"\"").toList())+" IN ACCESS SHARE MODE");}
        if(!scalar(connection,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND version IS NOT NULL").equals("22")
            || !scalar(connection,"SELECT coalesce(string_agg(version,',' ORDER BY version::integer),'') FROM public.flyway_schema_history WHERE success AND version IS NOT NULL").equals("1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22")
            || !scalar(connection,"SELECT count(*) FROM public.flyway_schema_history WHERE NOT success").equals("0"))throw new IllegalArgumentException();
        checkBalancedJournals(connection);
        String schema=digestQuery(connection,SCHEMA_SQL,"schema-v1",new long[]{0,0}).sha256();
        var tables=new ArrayList<Table>();long[] budget={0,0};
        for(String name:names) {
            var result=digestQuery(connection,"SELECT to_jsonb(t)::text FROM public.\""+name+"\" t ORDER BY (to_jsonb(t)::text) COLLATE \"C\"","table-v1:"+name,budget);
            tables.add(new Table(name,result.rows(),result.sha256()));
        }
        return new Inventory(major,schema,List.copyOf(tables));
    }

    private static void checkBalancedJournals(Connection connection)throws SQLException {
        String invalid=scalar(connection,"""
            SELECT count(*) FROM (
                SELECT t.id FROM public.ledger_transactions t LEFT JOIN public.ledger_entries e ON e.transaction_id=t.id
                GROUP BY t.id,t.amount,t.currency HAVING count(e.id)<>2
                OR count(*) FILTER(WHERE e.side='DEBIT' AND e.currency=t.currency AND e.amount=t.amount)<>1
                OR count(*) FILTER(WHERE e.side='CREDIT' AND e.currency=t.currency AND e.amount=t.amount)<>1
            ) invalid
            """);
        if(!invalid.equals("0"))throw new IllegalArgumentException();
    }

    private record DigestResult(long rows,String sha256) { }
    private static DigestResult digestQuery(Connection connection,String sql,String domain,long[] budget)throws SQLException {
        var digest=digest();digest.update(domain.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);long count=0;
        try(var statement=connection.createStatement()) {
            statement.setFetchSize(16);statement.setQueryTimeout(30);
            try(var rows=statement.executeQuery(sql)) {
                while(rows.next()) {
                    String value=rows.getString(1);
                    byte[] bytes=(domain.equals("schema-v1")?RecoverySchema.row(value):value).getBytes(StandardCharsets.UTF_8);
                    if(++budget[0]>MAX_ROWS || (budget[1]+=bytes.length)>MAX_DATA_BYTES)throw new IllegalArgumentException();
                    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());digest.update(bytes);count++;
                }
            }
        }
        return new DigestResult(count,HexFormat.of().formatHex(digest.digest()));
    }

    static String scalar(Connection connection,String sql)throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)){if(!result.next())throw new IllegalArgumentException();return result.getString(1);}
    }
    public static String sha256(byte[] bytes){return HexFormat.of().formatHex(digest().digest(bytes));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static String fileHash(Path file,long maximum)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();
        var digest=digest();long count=0;byte[] buffer=new byte[16384];
        try(var stream=Files.newInputStream(file)){int size;while((size=stream.read(buffer))!=-1){if((count+=size)>maximum)throw new IllegalArgumentException();digest.update(buffer,0,size);}}
        if(count==0)throw new IllegalArgumentException();return HexFormat.of().formatHex(digest.digest());
    }

    @FunctionalInterface interface Dump {void run(Path binary,Target source,String snapshot,Path directory)throws Exception;}
    static void dump(Path binary,Target source,String snapshot,Path directory)throws Exception {
        var process=new ProcessBuilder(binary.toString(),"--no-password","--format=custom","--no-owner","--no-privileges",
                "--snapshot="+snapshot,"--file="+directory.resolve("database.dump"));
        process.environment().keySet().removeIf(key->key.toUpperCase(Locale.ROOT).startsWith("PG"));
        process.environment().putAll(Map.of("PGHOST","127.0.0.1","PGPORT",Integer.toString(source.port()),"PGDATABASE",source.database(),
                "PGUSER",source.username(),"PGPASSWORD",source.password(),"PGCONNECT_TIMEOUT","5",
                "PGOPTIONS","-c statement_timeout=60000 -c lock_timeout=5000"));
        process.redirectOutput(ProcessBuilder.Redirect.DISCARD);process.redirectError(directory.resolve("pg-dump.log").toFile());
        Process child=process.start();
        try {
            if(!child.waitFor(120,TimeUnit.SECONDS))throw new IllegalArgumentException();
            if(child.exitValue()!=0 || Files.size(directory.resolve("pg-dump.log"))!=0)throw new IllegalArgumentException();
        } finally {
            if(child.isAlive()){child.destroy();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
        }
    }

    private static final String SCHEMA_SQL="""
        WITH definitions(kind,name,body) AS (
            SELECT 'database','settings',jsonb_build_array(pg_encoding_to_char(encoding),datcollate,datctype)::text
              FROM pg_database WHERE datname=current_database()
            UNION ALL SELECT 'table',c.relname::text,jsonb_build_array(c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,c.relreplident,c.reloptions)::text
              FROM pg_class c WHERE c.relnamespace='public'::regnamespace AND c.relkind='r'
            UNION ALL SELECT 'column',c.relname||':'||a.attnum,jsonb_build_array(a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,
                a.attidentity,a.attgenerated,pg_get_expr(d.adbin,d.adrelid),coll.collname)::text
              FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
              LEFT JOIN pg_collation coll ON coll.oid=a.attcollation
              WHERE c.relnamespace='public'::regnamespace AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
            UNION ALL SELECT 'constraint',c.relname||':'||x.conname,pg_get_constraintdef(x.oid,true)
              FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid WHERE c.relnamespace='public'::regnamespace
            UNION ALL SELECT 'index',c.relname,pg_get_indexdef(c.oid,0,true)||':'||i.indisvalid::text||':'||i.indisready::text
              FROM pg_class c JOIN pg_index i ON i.indexrelid=c.oid WHERE c.relnamespace='public'::regnamespace
            UNION ALL SELECT 'function',p.proname||':'||pg_get_function_identity_arguments(p.oid),pg_get_functiondef(p.oid)
              FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.prokind IN ('f','p','w')
            UNION ALL SELECT 'trigger',c.relname||':'||t.tgname,pg_get_triggerdef(t.oid,true)||':'||t.tgenabled::text
              FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace='public'::regnamespace AND NOT t.tgisinternal
            UNION ALL SELECT 'rule',c.relname||':'||r.rulename,pg_get_ruledef(r.oid,true)
              FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class WHERE c.relnamespace='public'::regnamespace
            UNION ALL SELECT 'policy',tablename||':'||policyname,jsonb_build_array(permissive,roles,cmd,qual,with_check)::text
              FROM pg_policies WHERE schemaname='public'
            UNION ALL SELECT 'extension',e.extname,e.extversion||':'||n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
        ) SELECT jsonb_build_array(kind,name,body)::text AS value FROM definitions ORDER BY kind COLLATE "C",name COLLATE "C",body COLLATE "C"
        """;
}
