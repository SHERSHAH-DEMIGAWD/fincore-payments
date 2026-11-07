package com.fincore.payments.operations;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RecoverySchemaTest {
    @Test void normalizesOnlyTheDumpReparseArrayCastEquivalence() {
        String original="CHECK (state::text = ANY (ARRAY['ALLOW'::character varying, 'DENY'::character varying]::text[]))";
        String restored="CHECK (state::text = ANY (ARRAY['ALLOW'::character varying::text, 'DENY'::character varying::text]))";
        assertThat(RecoverySchema.definition(original)).isEqualTo(RecoverySchema.definition(restored));
        assertThat(RecoverySchema.definition(original)).contains("'ALLOW'::text", "'DENY'::text");
    }
    @Test void preservesValuesOrderOperatorsAndBoundedOrDifferentTypes() {
        String original="CHECK (x = ANY (ARRAY['A'::character varying]::text[]))";
        assertThat(RecoverySchema.definition(original)).isNotEqualTo(RecoverySchema.definition(original.replace("'A'","'B'")));
        assertThat(RecoverySchema.definition(original)).isNotEqualTo(RecoverySchema.definition(original.replace(" = "," <> ")));
        for(String unsupported:new String[]{"ARRAY['A'::character varying(1)]::text[]","ARRAY['A'::character varying]","ARRAY['A'::citext]::text[]"})
            assertThat(RecoverySchema.definition(unsupported)).isEqualTo(unsupported);
    }
    @Test void neverRewritesQuotedContentOrFunctionBodies() {
        String quoted="CHECK (x = 'ARRAY[''A''::character varying]::text[]')";
        assertThat(RecoverySchema.definition(quoted)).isEqualTo(quoted);
        String escaped="CHECK (x = E'escaped\\'ARRAY[''A''::character varying]::text[]')";
        assertThat(RecoverySchema.definition(escaped)).isEqualTo(escaped);
        String function="[\"function\",\"f\",\"ARRAY['A'::character varying]::text[]\"]";
        assertThat(RecoverySchema.row(function)).contains("ARRAY['A'::character varying]::text[]");
        assertThat(RecoverySchema.definition("/* ARRAY['A'::character varying]::text[] */")).isEqualTo("/* ARRAY['A'::character varying]::text[] */");
    }
    @Test void escapedQuotesInsideArrayLiteralsRetainTheirExactValue() {
        assertThat(RecoverySchema.definition("ARRAY['A''B'::character varying]::text[]")).isEqualTo("ARRAY['A''B'::text]");
        String identifier="\"ARRAY['A'::character varying]::text[]\"";
        assertThat(RecoverySchema.definition(identifier)).isEqualTo(identifier);
    }
}
