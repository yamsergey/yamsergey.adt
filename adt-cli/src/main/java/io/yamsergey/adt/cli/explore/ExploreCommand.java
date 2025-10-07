package io.yamsergey.adt.cli.explore;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.module.ResolvedAndroidModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedGenericModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.android.model.project.Project;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.resolver.AndroidProjectResolver;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Interactive REPL for exploring Android project structure.
 * Avoids massive JSON dumps by providing targeted queries.
 */
@Command(name = "explore", description = "Interactive exploration of Android project structure")
public class ExploreCommand implements Callable<Integer> {

  private static final Logger logger = LoggerFactory.getLogger(ExploreCommand.class);

  @Parameters(index = "0", description = "Android project directory path")
  private String projectPath = ".";

  @picocli.CommandLine.Option(names = {"--verbose", "-v"}, description = "Show full stack traces")
  private boolean verbose = false;

  private Project project;
  private BufferedReader reader;

  @Override
  public Integer call() throws Exception {
    // Configure logging level based on verbose flag
    if (verbose) {
      ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
          LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
      root.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }

    File projectDir = new File(projectPath);

    if (!projectDir.exists() || !projectDir.isDirectory()) {
      System.err.println("Error: Project directory does not exist: " + projectPath);
      return 1;
    }

    if (verbose) {
      logger.info("Loading project from: {}", projectPath);
    }
    AndroidProjectResolver resolver = new AndroidProjectResolver(projectPath);
    var result = resolver.resolve();

    return switch (result) {
      case Success<Project> success -> {
        this.project = success.value();
        System.out.println("Project loaded: " + project.name());
        System.out.println("Modules: " + project.modules().size());
        startRepl();
        yield 0;
      }
      case Failure<Project> failure -> {
        logger.error("Failed to load project: {}", failure.description());
        if (failure.cause() != null) {
          if (verbose) {
            logger.error("Full stack trace:", failure.cause());
          } else {
            Throwable rootCause = failure.cause();
            while (rootCause.getCause() != null) {
              rootCause = rootCause.getCause();
            }
            System.err.println("Root cause: " + rootCause.getMessage());
            System.err.println("\nUse --verbose flag for full stack trace.");
          }
        }
        yield 1;
      }
    };
  }

  private void startRepl() throws Exception {
    reader = new BufferedReader(new InputStreamReader(System.in));

    printHelp();

    while (true) {
      System.out.print("\n> ");
      String line = reader.readLine();

      if (line == null || line.trim().equalsIgnoreCase("exit") || line.trim().equalsIgnoreCase("quit")) {
        System.out.println("Goodbye!");
        break;
      }

      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }

      try {
        processCommand(line);
      } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
      }
    }
  }

  private void processCommand(String command) {
    String[] parts = command.split("\\s+", 2);
    String cmd = parts[0].toLowerCase();
    String arg = parts.length > 1 ? parts[1] : "";

    switch (cmd) {
      case "help", "h" -> printHelp();
      case "summary" -> printSummary();
      case "modules", "list" -> listModules();
      case "module", "m" -> showModule(arg);
      case "deps" -> showDependencies(arg);
      case "variants", "v" -> showVariants(arg);
      case "roots", "sources" -> showSourceRoots(arg);
      case "find" -> findModule(arg);
      default -> System.out.println("Unknown command. Type 'help' for available commands.");
    }
  }

  private void printHelp() {
    System.out.println("\nAvailable commands:");
    System.out.println("  help                - Show this help message");
    System.out.println("  summary             - Show project summary");
    System.out.println("  modules             - List all modules");
    System.out.println("  module <name>       - Show details for specific module");
    System.out.println("  deps <module>       - Show dependencies for module");
    System.out.println("  variants <module>   - Show build variants for module");
    System.out.println("  roots <module>      - Show source roots for module");
    System.out.println("  find <pattern>      - Find modules matching pattern");
    System.out.println("  exit                - Exit explorer");
  }

  private void printSummary() {
    System.out.println("\nProject: " + project.name());
    System.out.println("Path: " + project.path());
    System.out.println("Total modules: " + project.modules().size());

    long androidModules = project.modules().stream()
        .filter(m -> m instanceof ResolvedAndroidModule)
        .count();
    long genericModules = project.modules().stream()
        .filter(m -> m instanceof ResolvedGenericModule)
        .count();

    System.out.println("  Android modules: " + androidModules);
    System.out.println("  Generic modules: " + genericModules);
  }

  private void listModules() {
    System.out.println("\nModules:");
    for (ResolvedModule module : project.modules()) {
      String type = switch (module) {
        case ResolvedAndroidModule am -> "[Android/" + am.type() + "]";
        case ResolvedGenericModule gm -> "[Generic]";
        default -> "[Unknown]";
      };
      System.out.println("  " + module.name() + " " + type);
    }
  }

  private void showModule(String moduleName) {
    if (moduleName.isEmpty()) {
      System.out.println("Usage: module <name>");
      return;
    }

    ResolvedModule module = findModuleByName(moduleName);
    if (module == null) {
      System.out.println("Module not found: " + moduleName);
      return;
    }

    System.out.println("\nModule: " + module.name());
    System.out.println("Path: " + module.path());

    switch (module) {
      case ResolvedAndroidModule am -> {
        System.out.println("Type: Android " + am.type());
        System.out.println("Build variants: " + am.buildVariants().size());
        System.out.println("Selected variant: " + am.selectedVariant().name());
        System.out.println("Dependencies: " + am.dependencies().size());
        System.out.println("Source roots: " + am.roots().size());
      }
      case ResolvedGenericModule gm -> {
        System.out.println("Type: Generic");
      }
      default -> {
        System.out.println("Type: Unknown");
      }
    }
  }

  private void showDependencies(String moduleName) {
    if (moduleName.isEmpty()) {
      System.out.println("Usage: deps <module>");
      return;
    }

    ResolvedModule module = findModuleByName(moduleName);
    if (module == null) {
      System.out.println("Module not found: " + moduleName);
      return;
    }

    if (!(module instanceof ResolvedAndroidModule am)) {
      System.out.println("Module is not an Android module");
      return;
    }

    System.out.println("\nDependencies for " + moduleName + ":");
    System.out.println("Total: " + am.dependencies().size());

    // Group by type
    var deps = am.dependencies();
    long external = deps.stream().filter(d -> d instanceof io.yamsergey.adt.tools.android.model.dependency.ExternalDependency).count();
    long local = deps.stream().filter(d -> d instanceof io.yamsergey.adt.tools.android.model.dependency.LocalDependency).count();

    System.out.println("  External: " + external);
    System.out.println("  Local: " + local);

    System.out.println("\nFirst 20 dependencies:");
    deps.stream()
        .limit(20)
        .forEach(d -> System.out.println("  - " + formatDependency(d)));

    if (deps.size() > 20) {
      System.out.println("  ... and " + (deps.size() - 20) + " more");
    }
  }

  private void showVariants(String moduleName) {
    if (moduleName.isEmpty()) {
      System.out.println("Usage: variants <module>");
      return;
    }

    ResolvedModule module = findModuleByName(moduleName);
    if (module == null) {
      System.out.println("Module not found: " + moduleName);
      return;
    }

    if (!(module instanceof ResolvedAndroidModule am)) {
      System.out.println("Module is not an Android module");
      return;
    }

    System.out.println("\nBuild variants for " + moduleName + ":");
    for (BuildVariant variant : am.buildVariants()) {
      String marker = variant.equals(am.selectedVariant()) ? " (selected)" : "";
      System.out.println("  - " + variant.displayName() + marker);
    }
  }

  private void showSourceRoots(String moduleName) {
    if (moduleName.isEmpty()) {
      System.out.println("Usage: roots <module>");
      return;
    }

    ResolvedModule module = findModuleByName(moduleName);
    if (module == null) {
      System.out.println("Module not found: " + moduleName);
      return;
    }

    if (!(module instanceof ResolvedAndroidModule am)) {
      System.out.println("Module is not an Android module");
      return;
    }

    System.out.println("\nSource roots for " + moduleName + ":");
    for (var root : am.roots()) {
      System.out.println("  [" + root.language() + "] " + root.path());
    }
  }

  private void findModule(String pattern) {
    if (pattern.isEmpty()) {
      System.out.println("Usage: find <pattern>");
      return;
    }

    System.out.println("\nModules matching '" + pattern + "':");
    boolean found = false;
    for (ResolvedModule module : project.modules()) {
      if (module.name().toLowerCase().contains(pattern.toLowerCase())) {
        System.out.println("  - " + module.name());
        found = true;
      }
    }

    if (!found) {
      System.out.println("  No matches found");
    }
  }

  private ResolvedModule findModuleByName(String name) {
    return project.modules().stream()
        .filter(m -> m.name().equals(name))
        .findFirst()
        .orElse(null);
  }

  private String formatDependency(Dependency dep) {
    return switch (dep) {
      case io.yamsergey.adt.tools.android.model.dependency.GradleJarDependency jar ->
          jar.groupId() + ":" + jar.artifactId() + ":" + jar.version();
      case io.yamsergey.adt.tools.android.model.dependency.GradleAarDependency aar ->
          aar.groupId() + ":" + aar.artifactId() + ":" + aar.version();
      case io.yamsergey.adt.tools.android.model.dependency.LocalJarDependency local ->
          "local:" + local.path();
      case io.yamsergey.adt.tools.android.model.dependency.ClassFolderDependency folder ->
          "classes:" + folder.path();
      default -> dep.getClass().getSimpleName();
    };
  }
}
