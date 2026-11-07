package com.fincore.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.ProjectionPreservation.*;
import com.fincore.payments.repository.ProjectionPreservationRepository.Stored;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.fincore.payments.service.ProjectionPreservationVerifier.*;
import static org.assertj.core.api.Assertions.*;

class ProjectionPreservationVerifierTest {
    private final ObjectMapper mapper=JsonMapper.builder().findAndAddModules().build();
    private final ProjectionPreservationVerifier verifier=new ProjectionPreservationVerifier(mapper);
    private final UUID id=UUID.randomUUID();
    private final Instant at=Instant.parse("2026-10-24T12:00:00Z");
    private final String body="{\"payload\":\"private-\u20b9\"}";
    private final ExportRecord row=new ExportRecord("RECEIPT","id",sha256(bytes(body)),body);
    private final ProjectionPurgePlan plan=new ProjectionPurgePlan(UUID.randomUUID(),1,UUID.randomUUID(),"primary","topic",0,3,3,
            at.minusSeconds(31*86400),at,30,"operator","plan","PLANNED_REVIEW_REQUIRED",false,List.of("KEEP"),
            List.of(new ProjectionPurgePlan.Artifact(row.kind(),row.identity(),row.sha256())));

    @Test void untouchedBundleRoundTripsAndReturnsTheExactBytes() {
        String json=verifier.encode(bundle(List.of(row)));
        assertThat(verifier.verify(stored(json),plan)).isEqualTo(bytes(json));
    }

    @ParameterizedTest @ValueSource(strings={"missing","duplicate","wrong_identity","wrong_body","wrong_row_digest","wrong_version","wrong_plan","wrong_actor"})
    void recomputingTheWholeFileDigestCannotHideManifestOrHeaderCorruption(String mutation) {
        List<ExportRecord> records=switch (mutation) {
            case "missing" -> List.of();
            case "duplicate" -> List.of(row,row);
            case "wrong_identity" -> List.of(new ExportRecord("RECEIPT","other",row.sha256(),body));
            case "wrong_body" -> List.of(new ExportRecord(row.kind(),row.identity(),row.sha256(),"{}"));
            case "wrong_row_digest" -> List.of(new ExportRecord(row.kind(),row.identity(),"0".repeat(64),body));
            default -> List.of(row);
        };
        Bundle b=bundle(records);
        if (mutation.equals("wrong_version")) b=new Bundle(2,id,plan,at,"operator","preserve",records);
        if (mutation.equals("wrong_plan")) b=new Bundle(1,id,null,at,"operator","preserve",records);
        if (mutation.equals("wrong_actor")) b=new Bundle(1,id,plan,at,"forged","preserve",records);
        String json=verifier.encode(b);
        assertThatThrownBy(() -> verifier.verify(stored(json),plan)).isInstanceOf(ProjectionPurgePlanConflictException.class);
    }

    @Test void truncatedOrChangedSavedBytesFailBeforeParsing() {
        String json=verifier.encode(bundle(List.of(row)));
        var stored=stored(json);
        assertThatThrownBy(() -> verifier.verify(new Stored(stored.metadata(),json+" "),plan))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> verifier.verify(stored("not-json"),plan)).isInstanceOf(ProjectionPurgePlanConflictException.class);
    }

    private Bundle bundle(List<ExportRecord> records) { return new Bundle(1,id,plan,at,"operator","preserve",records); }
    private Stored stored(String json) {
        return new Stored(new ProjectionPreservation(id,plan.requestId(),1,3,at,"operator","preserve",1,bytes(json).length,
                sha256(bytes(json)),"PRESERVED_IN_DATABASE_REVIEW_REQUIRED",false),json);
    }
}
