package com.fincore.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import com.fincore.payments.repository.ProjectionPreservationRepository.Stored;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Component
public class ProjectionPreservationVerifier {
    public static final int MAX_EXPORT_BYTES=8*1024*1024;
    private final ObjectMapper mapper;
    public ProjectionPreservationVerifier(ObjectMapper mapper) { this.mapper=mapper; }

    public String encode(ProjectionPreservation.Bundle bundle) {
        try {
            String json=mapper.writeValueAsString(bundle);
            if (bytes(json).length>MAX_EXPORT_BYTES) throw conflict("Serialized preservation exceeds the 8 MiB export budget.");
            return json;
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize preservation bundle.",e); }
    }

    public byte[] verify(Stored stored,ProjectionPurgePlan plan) {
        try {
            var p=stored.metadata();
            byte[] bytes=bytes(stored.json());
            if (bytes.length<1 || bytes.length>MAX_EXPORT_BYTES || bytes.length!=p.exportBytes()
                    || !sha256(bytes).equals(p.sha256())) throw invalid();
            var bundle=mapper.readValue(bytes,ProjectionPreservation.Bundle.class);
            if (p.formatVersion()!=1 || bundle.formatVersion()!=1 || !Objects.equals(bundle.preservationId(),p.requestId())
                    || !Objects.equals(bundle.plan(),plan) || !p.planId().equals(plan.requestId())
                    || p.expectedRevision()!=plan.expectedRevision() || !Objects.equals(bundle.preservedAt(),p.preservedAt())
                    || !Objects.equals(bundle.actor(),p.actor()) || !Objects.equals(bundle.reason(),p.reason())
                    || bundle.records()==null || bundle.records().size()!=p.artifactCount()
                    || p.artifactCount()!=plan.artifacts().size()) throw invalid();
            var manifest=bundle.records().stream().map(row -> {
                if (row==null || row.body()==null || !sha256(bytes(row.body())).equals(row.sha256())) throw invalid();
                return new ProjectionPurgePlan.Artifact(row.kind(),row.identity(),row.sha256());
            }).toList();
            if (!manifest.equals(plan.artifacts())) throw invalid();
            return bytes;
        } catch (java.io.IOException | NullPointerException e) { throw invalid(); }
    }
    public static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required SHA-256 is unavailable.",e); }
    }
    private static ProjectionPurgePlanConflictException invalid() { return conflict("Preservation content failed integrity verification."); }
    private static ProjectionPurgePlanConflictException conflict(String message) { return new ProjectionPurgePlanConflictException(message); }
}
