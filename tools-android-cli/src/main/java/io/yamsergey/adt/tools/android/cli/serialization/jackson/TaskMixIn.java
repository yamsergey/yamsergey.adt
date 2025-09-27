package io.yamsergey.adt.tools.android.cli.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;

public abstract class TaskMixIn {
  @JsonIgnore
  abstract org.gradle.tooling.model.GradleProject getProject(); // Ignore the back-reference from Task to Project
}
