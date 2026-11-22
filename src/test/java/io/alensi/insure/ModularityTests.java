package io.alensi.insure;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** ADR-0001: module boundaries are enforced here, so a violation fails the build. */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(InsureApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
