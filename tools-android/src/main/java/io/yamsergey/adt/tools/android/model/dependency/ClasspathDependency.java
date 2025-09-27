package io.yamsergey.adt.tools.android.model.dependency;

public record ClasspathDependency(
    String path,
    Dependency.Scope scope,
    String description) implements Dependency {
}
