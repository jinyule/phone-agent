package dev.phoneagent.buildlogic

import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Kover gates for `:core` (docs/design/engineering-baseline.md §3.1):
 * every class ≥ 90 % line coverage; classes under `core.policy` and `core.agent`
 * 100 % branch coverage, verified by the extra `critical` variant (`koverVerifyCritical`).
 */
class CoverageConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlinx.kover")
            extensions.configure<KoverProjectExtension> {
                currentProject {
                    createVariant(CRITICAL_VARIANT) { add("jvm") }
                }
                reports {
                    filters {
                        excludes { annotatedBy(EXCLUDE_ANNOTATION) }
                    }
                    verify {
                        rule("every core class: line coverage >= 90%") {
                            groupBy.set(GroupingEntityType.CLASS)
                            bound {
                                minValue.set(90)
                                coverageUnits.set(CoverageUnit.LINE)
                                aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
                            }
                        }
                    }
                    variant(CRITICAL_VARIANT) {
                        filtersAppend {
                            includes { packages(*CRITICAL_PACKAGES) }
                        }
                        verifyAppend {
                            rule("core.policy and core.agent: branch coverage = 100%") {
                                groupBy.set(GroupingEntityType.CLASS)
                                bound {
                                    minValue.set(100)
                                    coverageUnits.set(CoverageUnit.BRANCH)
                                    aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
                                }
                            }
                        }
                    }
                }
            }
            tasks.named("check") {
                dependsOn("koverVerify", "koverVerify${CRITICAL_VARIANT.replaceFirstChar(Char::uppercaseChar)}")
            }
        }

    private companion object {
        const val CRITICAL_VARIANT = "critical"
        const val EXCLUDE_ANNOTATION = "dev.phoneagent.core.ExcludeFromCoverage"
        val CRITICAL_PACKAGES = arrayOf("dev.phoneagent.core.policy", "dev.phoneagent.core.agent")
    }
}
