package io.yamsergey.adt.cli.inspect;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.yamsergey.adt.cli.serialization.jackson.CompositeSerializerModifier;
import io.yamsergey.adt.cli.serialization.jackson.ParentIgnoreMixIn;
import io.yamsergey.adt.cli.serialization.jackson.ProjectMixIn;
import io.yamsergey.adt.cli.serialization.jackson.SafeSerializerModifier;
import io.yamsergey.adt.cli.serialization.jackson.TaskMixIn;
import io.yamsergey.adt.tools.android.gradle.FetchAndroidDependencies;
import io.yamsergey.adt.tools.android.gradle.FetchAndroidDsl;
import io.yamsergey.adt.tools.android.gradle.FetchAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.adt.tools.android.gradle.FetchGradleProject;
import io.yamsergey.adt.tools.android.gradle.FetchIdeaProject;
import io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Interactive REPL for inspecting raw Gradle models and testing fetchers.
 * Provides access to all Gradle model fetchers and utility methods.
 */
@Command(name = "inspect", description = "Interactive inspection of raw Gradle models")
public class InspectCommand implements Callable<Integer> {

  private static final Logger logger = LoggerFactory.getLogger(InspectCommand.class);

  @Parameters(index = "0", description = "Android project directory path")
  private String projectPath = ".";

  @picocli.CommandLine.Option(names = {"--verbose", "-v"}, description = "Show full stack traces")
  private boolean verbose = false;

  @picocli.CommandLine.Option(names = {"--module", "-m"}, description = "Module path (e.g., :app)")
  private String modulePath = null;

  @picocli.CommandLine.Option(names = {"--fetch", "-f"}, description = "Fetcher to run (non-interactive mode)")
  private String fetcherName = null;

  @picocli.CommandLine.Option(names = {"--output", "-o"}, description = "Output file for fetched model")
  private String outputFile = null;

  @picocli.CommandLine.Option(names = {"--list-modules"}, description = "List all modules and exit")
  private boolean listModulesOnly = false;

  @picocli.CommandLine.Option(names = {"--list-fetchers"}, description = "List all fetchers and exit")
  private boolean listFetchersOnly = false;

  private ProjectConnection connection;
  private GradleProject rootProject;
  private GradleProject currentModule;
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
      logger.info("Connecting to Gradle project: {}", projectPath);
    }

    var connectionResult = GradleProjectUtils.establishConnection(projectDir);

    return switch (connectionResult) {
      case Success<ProjectConnection> success -> {
        this.connection = success.value();

        // Fetch root Gradle project
        var projectResult = connection.action(FetchGradleProject.builder().build()).run();

        switch (projectResult) {
          case Success<GradleProject> projectSuccess -> {
            this.rootProject = projectSuccess.value();
            this.currentModule = rootProject;

            // Non-interactive modes
            if (listFetchersOnly) {
              listFetchers();
              connection.close();
              yield 0;
            }

            if (listModulesOnly) {
              listModules();
              connection.close();
              yield 0;
            }

            // Non-interactive fetch mode
            if (fetcherName != null) {
              // Change to specified module if provided
              if (modulePath != null) {
                GradleProject target = findModule(modulePath);
                if (target == null) {
                  System.err.println("Module not found: " + modulePath);
                  connection.close();
                  yield 1;
                }
                currentModule = target;
              }

              // Fetch the model
              try {
                fetchModel(fetcherName);

                // Save if output file specified
                if (outputFile != null && lastFetchedModel != null) {
                  saveModel(outputFile);
                }

                connection.close();
                yield lastFetchedModel != null ? 0 : 1;
              } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                if (verbose) {
                  e.printStackTrace();
                }
                connection.close();
                yield 1;
              }
            }

            // Interactive mode
            System.out.println("Connected to project: " + rootProject.getName());
            System.out.println("Root path: " + rootProject.getPath());
            startRepl();
            yield 0;
          }
          case Failure<GradleProject> failure -> {
            logger.error("Failed to fetch root project: {}", failure.description());
            connection.close();
            yield 1;
          }
        }
      }
      case Failure<ProjectConnection> failure -> {
        logger.error("Failed to connect: {}", failure.description());
        if (failure.cause() != null && verbose) {
          logger.error("Full stack trace:", failure.cause());
        }
        yield 1;
      }
    };
  }

  private void startRepl() throws Exception {
    reader = new BufferedReader(new InputStreamReader(System.in));

    printHelp();

    while (true) {
      System.out.print("\n[" + currentModule.getPath() + "]> ");
      String line = reader.readLine();

      if (line == null || line.trim().equalsIgnoreCase("exit") || line.trim().equalsIgnoreCase("quit")) {
        System.out.println("Goodbye!");
        connection.close();
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
        if (verbose) {
          e.printStackTrace();
        }
      }
    }
  }

  private void processCommand(String command) throws Exception {
    String[] parts = command.split("\\s+", 2);
    String cmd = parts[0].toLowerCase();
    String arg = parts.length > 1 ? parts[1] : "";

    switch (cmd) {
      case "help", "h" -> printHelp();
      case "modules", "list" -> listModules();
      case "cd" -> changeModule(arg);
      case "pwd" -> System.out.println("Current: " + currentModule.getPath());
      case "info" -> showModuleInfo();
      case "utils" -> runUtils();
      case "fetchers" -> listFetchers();
      case "fetch" -> fetchModel(arg);
      case "save" -> saveModel(arg);
      default -> System.out.println("Unknown command. Type 'help' for available commands.");
    }
  }

  private void printHelp() {
    System.out.println("\nAvailable commands:");
    System.out.println("  help                - Show this help message");
    System.out.println("  modules             - List all modules in project");
    System.out.println("  cd <path>           - Change current module (e.g., 'cd :app', 'cd ..' for parent)");
    System.out.println("  pwd                 - Show current module path");
    System.out.println("  info                - Show current module information");
    System.out.println("  utils               - Run utility methods on current module");
    System.out.println("  fetchers            - List available model fetchers");
    System.out.println("  fetch <fetcher>     - Fetch model using specified fetcher");
    System.out.println("  save <file>         - Save last fetched model to JSON file");
    System.out.println("  exit                - Exit inspector");
  }

  private void listModules() {
    System.out.println("\nModules:");
    listModulesRecursive(rootProject, 0);
  }

  private void listModulesRecursive(GradleProject module, int depth) {
    String indent = "  ".repeat(depth);
    String marker = module.equals(currentModule) ? " *" : "";
    boolean isAndroid = GradleProjectUtils.isAndroidProject(module);
    String androidMarker = isAndroid ? " [Android]" : "";
    System.out.println(indent + module.getPath() + androidMarker + marker);
    for (GradleProject child : module.getChildren()) {
      listModulesRecursive(child, depth + 1);
    }
  }

  private void changeModule(String path) {
    if (path.isEmpty()) {
      System.out.println("Usage: cd <path>");
      return;
    }

    if (path.equals("..")) {
      if (currentModule.getParent() != null) {
        currentModule = currentModule.getParent();
        System.out.println("Changed to: " + currentModule.getPath());
      } else {
        System.out.println("Already at root");
      }
      return;
    }

    if (path.equals(".")) {
      return;
    }

    GradleProject target = findModule(path);
    if (target != null) {
      currentModule = target;
      System.out.println("Changed to: " + currentModule.getPath());
    } else {
      System.out.println("Module not found: " + path);
    }
  }

  private GradleProject findModule(String path) {
    return findModuleRecursive(rootProject, path);
  }

  private GradleProject findModuleRecursive(GradleProject module, String path) {
    if (module.getPath().equals(path)) {
      return module;
    }
    for (GradleProject child : module.getChildren()) {
      GradleProject found = findModuleRecursive(child, path);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private void showModuleInfo() {
    System.out.println("\nModule Information:");
    System.out.println("  Path: " + currentModule.getPath());
    System.out.println("  Name: " + currentModule.getName());
    System.out.println("  Directory: " + currentModule.getProjectDirectory());
    System.out.println("  Build File: " + currentModule.getBuildScript().getSourceFile());
    System.out.println("  Children: " + currentModule.getChildren().size());
    if (currentModule.getParent() != null) {
      System.out.println("  Parent: " + currentModule.getParent().getPath());
    }
  }

  private void runUtils() {
    System.out.println("\nUtility Methods:");
    System.out.println("  isAndroidProject(): " + GradleProjectUtils.isAndroidProject(currentModule));
  }

  private void listFetchers() {
    System.out.println("\nAvailable Fetchers:");
    System.out.println("  android-project        - Fetch AndroidProject (v2 API)");
    System.out.println("  basic-android-project  - Fetch BasicAndroidProject");
    System.out.println("  gradle-project         - Fetch GradleProject");
    System.out.println("  idea-project           - Fetch IdeaProject");
    System.out.println("  android-dependencies   - Fetch AndroidDependencies");
    System.out.println("  android-dsl            - Fetch AndroidDsl");
  }

  private Object lastFetchedModel = null;

  private void fetchModel(String fetcherName) throws Exception {
    if (fetcherName.isEmpty()) {
      System.out.println("Usage: fetch <fetcher-name>");
      listFetchers();
      return;
    }

    System.out.println("Fetching " + fetcherName + " for " + currentModule.getPath() + "...");

    Result<?> result = null;

    switch (fetcherName.toLowerCase()) {
      case "android-project" -> {
        result = connection.action(new FetchAndroidProject(currentModule)).run();
      }
      case "basic-android-project" -> {
        result = connection.action(new FetchBasicAndroidProject(currentModule)).run();
      }
      case "gradle-project" -> {
        result = connection.action(FetchGradleProject.builder().build()).run();
      }
      case "idea-project" -> {
        result = connection.action(new FetchIdeaProject(currentModule)).run();
      }
      case "android-dependencies" -> {
        System.out.println("Note: android-dependencies requires a build variant. Skipping for now.");
        return;
      }
      case "android-dsl" -> {
        result = connection.action(new FetchAndroidDsl(currentModule)).run();
      }
      default -> {
        System.out.println("Unknown fetcher: " + fetcherName);
        listFetchers();
        return;
      }
    }

    switch (result) {
      case Success<?> success -> {
        lastFetchedModel = success.value();
        System.out.println("✓ Success");
        if (success.description() != null) {
          System.out.println("  Description: " + success.description());
        }
        System.out.println("  Model type: " + lastFetchedModel.getClass().getName());
        System.out.println("  Use 'save <file>' to save to JSON");
      }
      case Failure<?> failure -> {
        lastFetchedModel = null;
        System.out.println("✗ Failed");
        if (failure.description() != null) {
          System.out.println("  Description: " + failure.description());
        }
        if (failure.cause() != null && verbose) {
          failure.cause().printStackTrace();
        }
      }
      case null -> System.out.println("✗ Null result");
    }
  }

  private void saveModel(String filename) throws Exception {
    if (filename.isEmpty()) {
      System.out.println("Usage: save <filename>");
      return;
    }

    if (lastFetchedModel == null) {
      System.out.println("No model to save. Use 'fetch' first.");
      return;
    }

    File outputFile = new File(filename);
    System.out.println("Saving to: " + outputFile.getAbsolutePath());

    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    // Register safe serialization
    SimpleModule module = new SimpleModule();
    var modifiers = new ArrayList<com.fasterxml.jackson.databind.ser.BeanSerializerModifier>();
    modifiers.add(new SafeSerializerModifier());
    module.setSerializerModifier(new CompositeSerializerModifier(modifiers));
    mapper.registerModule(module);

    // Add mixins to handle circular references
    mapper.addMixIn(Object.class, ParentIgnoreMixIn.class);
    mapper.addMixIn(org.gradle.tooling.model.Task.class, TaskMixIn.class);
    mapper.addMixIn(org.gradle.tooling.model.GradleProject.class, ProjectMixIn.class);

    try {
      mapper.writeValue(outputFile, lastFetchedModel);
      System.out.println("✓ Saved successfully");
    } catch (Exception e) {
      System.err.println("✗ Failed to save: " + e.getMessage());
      if (verbose) {
        e.printStackTrace();
      }
    }
  }
}
