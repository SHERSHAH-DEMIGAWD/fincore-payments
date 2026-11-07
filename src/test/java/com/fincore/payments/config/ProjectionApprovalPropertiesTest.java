package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionApprovalPropertiesTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(ProjectionOperationsConfiguration.class);

    @Test
    void defaultsToOneHourWithoutStartingInfrastructure() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProjectionApprovalProperties.class)).isEqualTo(new ProjectionApprovalProperties(60));
        });
    }

    @ParameterizedTest
    @ValueSource(ints={1,1440})
    void bindsBothPermittedValidityBoundaries(int minutes) {
        runner.withPropertyValues("fincore.projection-approvals.validity-minutes="+minutes).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProjectionApprovalProperties.class)).isEqualTo(new ProjectionApprovalProperties(minutes));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"0","-1","1441","oops","1.5","1e2","2147483648"})
    void invalidValidityConfigurationFailsStartup(String value) {
        runner.withPropertyValues("fincore.projection-approvals.validity-minutes="+value)
                .run(context -> assertThat(context).hasFailed());
    }
}
