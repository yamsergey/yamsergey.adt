package io.yamsergey.adt.tools.android.model.dependency;

import java.util.Collection;

import lombok.Builder;

@Builder(toBuilder = true)
public record AarDependency(
    String path,
    Collection<String> resolvedJars,
    String description,
    Dependency.Scope scope) implements Dependency {
}
