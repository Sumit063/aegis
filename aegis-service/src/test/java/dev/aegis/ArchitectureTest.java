package dev.aegis;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Dependency rules from docs/engineering/ARCHITECTURE.md §3. Fix the code, never these rules. */
@AnalyzeClasses(packages = "dev.aegis", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPureJava = classes()
            .that()
            .resideInAPackage("dev.aegis.domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "dev.aegis.domain..")
            .as("domain depends only on java.* and itself");

    @ArchTest
    static final ArchRule applicationDependsOnlyOnCore = classes()
            .that()
            .resideInAPackage("dev.aegis.application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "dev.aegis.domain..",
                    "dev.aegis.application..",
                    "dev.aegis.provider.spi..",
                    "org.springframework.stereotype..",
                    "org.springframework.transaction.annotation..",
                    "org.springframework.transaction.support..",
                    "org.slf4j..",
                    "org.jspecify.annotations..")
            .as("application depends only on domain, provider SPI, transaction annotations and logging");

    @ArchTest
    static final ArchRule providersArePlugins = noClasses()
            .that()
            .resideInAPackage("dev.aegis.provider..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.aegis.application..", "dev.aegis.adapter..", "dev.aegis.config..")
            .as("providers depend only on provider SPI and domain");

    @ArchTest
    static final ArchRule providersAreIndependent = slices().matching("dev.aegis.provider.(*)..")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAPackage("dev.aegis.provider.spi.."))
            .as("providers never depend on each other");

    @ArchTest
    static final ArchRule adaptersAreIndependent = slices().matching("dev.aegis.adapter.(*).(*)..")
            .should()
            .notDependOnEachOther()
            .as("adapters never depend on each other");

    @ArchTest
    static final ArchRule jpaStaysInPersistenceAdapter = noClasses()
            .that()
            .resideOutsideOfPackage("dev.aegis.adapter.out.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.persistence..")
            .as("JPA is used only in adapter.out.persistence");

    @ArchTest
    static final ArchRule coreDoesNotReachOutward = noClasses()
            .that()
            .resideInAnyPackage("dev.aegis.domain..", "dev.aegis.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.aegis.adapter..", "dev.aegis.config..")
            .as("the core never depends on adapters or configuration");

    @ArchTest
    static final ArchRule noCycles =
            slices().matching("dev.aegis.(*)..").should().beFreeOfCycles().as("no package cycles");

    @ArchTest
    static final ArchRule controllersLiveInWebAdapter = classes()
            .that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should()
            .resideInAPackage("dev.aegis.adapter.in.web.controller")
            .andShould()
            .haveSimpleNameEndingWith("Controller")
            .as("REST controllers are *Controller classes in adapter.in.web.controller");

    @ArchTest
    static final ArchRule useCasesAreServices = classes()
            .that()
            .areAnnotatedWith(org.springframework.stereotype.Service.class)
            .should()
            .resideInAPackage("dev.aegis.application.service")
            .andShould()
            .haveSimpleNameEndingWith("Service")
            .as("use-case implementations are *Service classes in application.service");

    @ArchTest
    static final ArchRule inboundPortsAreUseCases = classes()
            .that()
            .resideInAPackage("dev.aegis.application.port.in")
            .and()
            .areInterfaces()
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .haveSimpleNameEndingWith("UseCase")
            .as("inbound ports are *UseCase interfaces");

    @ArchTest
    static final ArchRule outboundPortsAreNamedForTheirRole = classes()
            .that()
            .resideInAPackage("dev.aegis.application.port.out")
            .and()
            .areInterfaces()
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .haveSimpleNameEndingWith("Port")
            .orShould()
            .haveSimpleNameEndingWith("Repository")
            .as("outbound ports are *Port or *Repository interfaces");

    @ArchTest
    static final ArchRule entitiesLiveTogether = classes()
            .that()
            .areAnnotatedWith(jakarta.persistence.Entity.class)
            .should()
            .resideInAPackage("dev.aegis.adapter.out.persistence.entity")
            .andShould()
            .haveSimpleNameEndingWith("Entity")
            .as("JPA entities are *Entity classes in adapter.out.persistence.entity");

    @ArchTest
    static final ArchRule springDataRepositoriesLiveTogether = classes()
            .that()
            .areAssignableTo(org.springframework.data.repository.Repository.class)
            .should()
            .resideInAPackage("dev.aegis.adapter.out.persistence.repository")
            .as("Spring Data repositories live in adapter.out.persistence.repository");
}
