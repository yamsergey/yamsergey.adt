package io.yamsergey.adt.tools.android.model.dependency;

import lombok.Builder;

@Builder(toBuilder = true)
public record JarDependency(
    String path,
    Dependency.Scope scope,
    String description) implements Dependency {
}
