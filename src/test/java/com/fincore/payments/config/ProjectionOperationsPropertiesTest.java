package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionOperationsPropertiesTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(ProjectionOperationsConfiguration.class);

    @Test
    void appliesDefaultsWithoutStartingInfrastructure() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProjectionOperationsProperties.class))
                    .isEqualTo(new ProjectionOperationsProperties(30,100000));
        });
    }

    @Test
    void bindsExplicitReviewWindowAndComparisonBudget() {
        runner.withPropertyValues("fincore.projection-operations.review-after-days=60",
                "fincore.projection-operations.max-comparison-rows=2000").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProjectionOperationsProperties.class))
                    .isEqualTo(new ProjectionOperationsProperties(60,2000));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"review-after-days=0","review-after-days=-1","review-after-days=3651",
            "max-comparison-rows=0","max-comparison-rows=-1","max-comparison-rows=1000001",
            "max-comparison-rows=oops"})
    void rejectsInvalidOperatingLimitsAtStartup(String value) {
        runner.withPropertyValues("fincore.projection-operations." + value)
                .run(context -> assertThat(context).hasFailed());
    }
}
