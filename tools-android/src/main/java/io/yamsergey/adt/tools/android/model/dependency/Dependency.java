package io.yamsergey.adt.tools.android.model.dependency;

public sealed interface Dependency permits AarDependency, JarDependency, ClasspathDependency, ClassFolderDependency {
  public static enum Scope {
    COMPILE,
    TEST,
    RUNTIME
  }

  String path();

  String description();

  Scope scope();
}
