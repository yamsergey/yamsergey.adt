package io.yamsergey.adt.tools.android.cli.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;

public abstract class ProjectMixIn {
  @JsonIgnore
  abstract org.gradle.tooling.model.GradleProject getParent(); // Also ignore the parent reference if it causes issues
}
