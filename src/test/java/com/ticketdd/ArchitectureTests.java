package com.ticketdd;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the boundaries documented in CLAUDE.md so they hold even as the
 * codebase grows: domain stays framework-free, layers only depend inward,
 * and the order/booking bounded contexts never call each other directly
 * (cross-context communication is expected to go through Kafka instead).
 */
@AnalyzeClasses(packages = "com.ticketdd", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_frameworks =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "jakarta.transaction..",
                            "org.apache.kafka..",
                            "redis.clients..",
                            "io.lettuce.."
                    );

    @ArchTest
    static final ArchRule domain_must_not_depend_on_other_layers =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..application..", "..infrastructure..", "..interfaces.."
                    );

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure_or_interfaces =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..infrastructure..", "..interfaces.."
                    );

    @ArchTest
    static final ArchRule order_must_not_depend_on_booking =
            noClasses().that().resideInAPackage("com.ticketdd.order..")
                    .should().dependOnClassesThat().resideInAPackage("com.ticketdd.booking..");

    @ArchTest
    static final ArchRule booking_must_not_depend_on_order =
            noClasses().that().resideInAPackage("com.ticketdd.booking..")
                    .should().dependOnClassesThat().resideInAPackage("com.ticketdd.order..");

}
