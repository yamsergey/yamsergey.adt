package io.yamsergey.adt.tools.android.resolver;

import java.util.List;

import javax.annotation.Nonnull;

import org.gradle.tooling.model.GradleProject;

import io.yamsergey.adt.tools.android.model.module.ResolvedGenericModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;

/**
 * Specialized resolver for generic (non-Android) Gradle modules.
 *
 * <p>Generic modules include Java libraries, Kotlin libraries, and other JVM-based
 * modules that don't use the Android Gradle Plugin. This resolver provides basic
 * project metadata (name and path) but doesn't attempt to resolve source roots
 * or dependencies since they would require different resolution strategies specific
 * to the module type (Java, Kotlin, Scala, etc.).</p>
 *
 * <p>Future enhancements could include:</p>
 * <ul>
 *   <li>Java source set resolution using Gradle's Java plugin model</li>
 *   <li>Kotlin source set resolution using Kotlin plugin model</li>
 *   <li>Generic dependency resolution using Gradle's dependency APIs</li>
 *   <li>Support for other JVM languages (Scala, Groovy, etc.)</li>
 * </ul>
 *
 * @see ResolvedGenericModule
 */
public class GenericModuleResolver {

    /**
     * Resolves a generic (non-Android) Gradle module with basic project information.
     *
     * <p>This implementation provides essential project metadata but leaves source
     * root and dependency resolution for future enhancement. The design allows for
     * easy extension to support specific module types without breaking existing
     * functionality.</p>
     *
     * @param subProject the generic Gradle project to resolve
     * @return {@link ResolvedGenericModule} with basic project information
     */
    public ResolvedModule resolve(@Nonnull GradleProject subProject) {
        return ResolvedGenericModule.builder()
                .name(subProject.getName())
                .path(subProject.getPath())
                .roots(List.of()) // Generic modules don't have Android-specific source roots
                .dependencies(List.of()) // Generic modules don't resolve dependencies in this context
                .build();
    }
}