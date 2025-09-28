package io.yamsergey.adt.tools.android.resolver;

import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.isAndroidProject;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidProject;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidProject;
import io.yamsergey.adt.tools.android.gradle.utils.VariantUtils;
import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.module.FailedModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedAndroidModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedGenericModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.model.variant.ResolvedVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * Default implementation of {@link ModuleResolutionStrategy} that provides comprehensive
 * module resolution with Gradle project tree flattening and dependency deduplication.
 *
 * <p>This resolver addresses the key limitations of Gradle's hierarchical project structure
 * for Android development tooling by:</p>
 *
 * <ul>
 *   <li><strong>Flattening Gradle tree structure</strong> - Recursively processes all subprojects
 *       in a multi-module Gradle build, regardless of nesting depth</li>
 *   <li><strong>Proper module type detection</strong> - Uses {@link GradleProjectUtils#isAndroidProject(GradleProject)}
 *       to distinguish Android modules from generic Gradle modules</li>
 *   <li><strong>Dependency deduplication</strong> - Leverages {@link AndroidModuleDependencyResolver}
 *       which flattens GraphItem dependency trees and deduplicates transitive dependencies</li>
 *   <li><strong>Comprehensive error handling</strong> - Uses Result pattern for type-safe error propagation</li>
 * </ul>
 *
 * <h3>Module Resolution Strategy</h3>
 * <p>For each discovered module, this resolver:</p>
 * <ol>
 *   <li>Detects if the module is an Android project using AndroidManifest.xml presence</li>
 *   <li>For Android modules: Resolves build variants, source roots, and full dependency tree</li>
 *   <li>For generic modules: Creates {@link ResolvedGenericModule} with basic project info</li>
 *   <li>Returns {@link FailedModule} for any resolution errors with detailed context</li>
 * </ol>
 *
 * <h3>Dependency Processing</h3>
 * <p>Dependency resolution is delegated to {@link AndroidModuleDependencyResolver} which:</p>
 * <ul>
 *   <li>Fetches {@link VariantDependencies} for the selected build variant</li>
 *   <li>Recursively processes {@link GraphItem} dependency trees using parallel streams</li>
 *   <li>Automatically deduplicates dependencies that appear multiple times in transitive chains</li>
 *   <li>Converts GraphItems to strongly-typed {@link Dependency} objects (JAR/AAR)</li>
 * </ul>
 *
 * @see ModuleResolutionStrategy
 * @see AndroidModuleDependencyResolver
 * @see GradleProjectUtils#isAndroidProject(GradleProject)
 */
public class DefaultModuleResolver implements ModuleResolutionStrategy {

    /**
     * Resolves all modules in a Gradle project by flattening the project tree structure
     * and processing each module according to its type (Android vs generic).
     *
     * @param connection established connection to the Gradle project
     * @param rootProject the root Gradle project to process
     * @param selectedBuildVariant the build variant to use for Android module resolution
     * @return list of resolved modules with full dependency information
     */
    @Override
    public List<ResolvedModule> resolveModules(
            @Nonnull ProjectConnection connection,
            @Nonnull GradleProject rootProject,
            @Nonnull BuildVariant selectedBuildVariant) {
        return flattenGradleProjectTree(rootProject)
                .stream()
                .map(subProject -> resolveModule(subProject, connection, rootProject, selectedBuildVariant))
                .collect(Collectors.toList());
    }

    /**
     * Recursively flattens the Gradle project tree structure to extract all subprojects
     * regardless of nesting depth. This is essential because Gradle projects can have
     * deeply nested module hierarchies that need to be processed uniformly.
     *
     * <p>The flattening ensures that all modules (app, library, feature modules, etc.)
     * are discovered and processed, even in complex multi-module Android applications
     * with nested module structures.</p>
     *
     * @param project the Gradle project to flatten
     * @return flat list of all subprojects found in the tree
     */
    private List<GradleProject> flattenGradleProjectTree(GradleProject project) {
        return project.getChildren()
                .stream()
                .flatMap(child -> {
                    List<GradleProject> childModules = flattenGradleProjectTree(child);
                    childModules.add(0, child);
                    return childModules.stream();
                })
                .collect(Collectors.toList());
    }

    /**
     * Resolves a single Gradle module by determining its type and delegating to
     * the appropriate resolution strategy.
     *
     * @param subProject the Gradle project to resolve
     * @param connection the Gradle project connection
     * @param rootProject the root project for context
     * @param selectedBuildVariant the build variant for Android modules
     * @return resolved module information or failure details
     */
    private ResolvedModule resolveModule(
            GradleProject subProject,
            ProjectConnection connection,
            GradleProject rootProject,
            BuildVariant selectedBuildVariant) {
        if (isAndroidProject(subProject)) {
            return resolveAndroidModule(subProject, connection, rootProject, selectedBuildVariant);
        } else {
            return resolveGenericModule(subProject);
        }
    }

    /**
     * Resolves an Android module with complete build variant, dependency, and source information.
     * This method orchestrates the complex Android-specific resolution process including:
     * <ul>
     *   <li>Fetching Android project model from Gradle Tooling API</li>
     *   <li>Resolving all available build variants for the module</li>
     *   <li>Selecting appropriate build variant using {@link VariantUtils}</li>
     *   <li>Resolving source roots (both regular and generated sources)</li>
     *   <li>Resolving complete dependency tree with transitive dependencies</li>
     * </ul>
     *
     * @param subProject the Android Gradle project to resolve
     * @param connection the Gradle project connection
     * @param rootProject the root project for context
     * @param selectedBuildVariant the build variant to resolve dependencies for
     * @return {@link ResolvedAndroidModule} with complete information or {@link FailedModule} on error
     */
    private ResolvedModule resolveAndroidModule(
            GradleProject subProject,
            ProjectConnection connection,
            GradleProject rootProject,
            BuildVariant selectedBuildVariant) {
        var androidProjectResult = connection.action(
                FetchAndroidProject.builder().gradleProject(subProject).build()).run();

        return switch (androidProjectResult) {
            case Success<AndroidProject> success -> {
                var moduleBuildVariants = new BuildVariantsResolver(success.value()).resolve();

                yield switch (moduleBuildVariants) {
                    case Success<Collection<BuildVariant>> variants -> {
                        var resolvedModuleSources = new AndroidModuleSourcesResolver(
                                subProject, connection, rootProject).resolve();
                        var moduleBuildVariant = VariantUtils.chooseBuildVariant(
                                selectedBuildVariant, variants.value());
                        var resolvedVariantResult = new AndroidModuleDependencyResolver(
                                moduleBuildVariant,
                                subProject,
                                connection,
                                success.value()).resolve();

                        yield switch (resolvedModuleSources) {
                            case Success<Collection<SourceRoot>> sources -> switch (resolvedVariantResult) {
                                case Success<ResolvedVariant> resolvedVariant -> ResolvedAndroidModule.builder()
                                        .name(subProject.getName())
                                        .path(subProject.getPath())
                                        .buildVariants(variants.value())
                                        .dependencies(resolvedVariant.value().dependencies())
                                        .roots(resolvedVariant.value().generatedRoots())
                                        .roots(sources.value())
                                        .build();
                                case Failure<ResolvedVariant> failure -> FailedModule.from(failure, subProject);
                                default -> FailedModule.builder()
                                        .name(subProject.getName())
                                        .path(subProject.getPath())
                                        .details("Unknown result of module variant resolution for: " + subProject.getPath())
                                        .build();
                            };
                            case Failure<Collection<SourceRoot>> failure -> FailedModule.from(failure, subProject);
                            default -> FailedModule.builder()
                                    .name(subProject.getName())
                                    .path(subProject.getPath())
                                    .details("Unknown result for source set resolution for: " + subProject.getPath())
                                    .build();
                        };
                    }
                    case Failure<Collection<BuildVariant>> failure -> FailedModule.from(failure, subProject);
                    default -> FailedModule.builder()
                            .name(subProject.getName())
                            .path(subProject.getPath())
                            .details("Unknown build variant resolution result")
                            .build();
                };
            }
            case Failure<AndroidProject> failure -> FailedModule.from(failure, subProject);
            default -> FailedModule.builder()
                    .name(subProject.getName())
                    .path(subProject.getPath())
                    .details("Unknown android project resolution result")
                    .build();
        };
    }

    /**
     * Resolves a generic (non-Android) Gradle module with basic project information.
     * Generic modules include Java libraries, Kotlin libraries, and other JVM-based
     * modules that don't use the Android Gradle Plugin.
     *
     * <p>For generic modules, this implementation provides basic project metadata
     * (name and path) but doesn't attempt to resolve source roots or dependencies
     * since they would require different resolution strategies specific to the
     * module type (Java, Kotlin, Scala, etc.).</p>
     *
     * @param subProject the generic Gradle project to resolve
     * @return {@link ResolvedGenericModule} with basic project information
     */
    private ResolvedModule resolveGenericModule(GradleProject subProject) {
        return ResolvedGenericModule.builder()
                .name(subProject.getName())
                .path(subProject.getPath())
                .roots(List.of()) // Generic modules don't have Android-specific source roots
                .dependencies(List.of()) // Generic modules don't resolve dependencies in this context
                .build();
    }

}