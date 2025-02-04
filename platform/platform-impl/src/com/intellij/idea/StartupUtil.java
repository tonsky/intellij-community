// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.idea;

import com.intellij.Patches;
import com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory;
import com.intellij.diagnostic.Activity;
import com.intellij.diagnostic.LoadingPhase;
import com.intellij.diagnostic.StartUpMeasurer;
import com.intellij.ide.CliResult;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.IdeRepaintManager;
import com.intellij.ide.customize.AbstractCustomizeWizardStep;
import com.intellij.ide.customize.CustomizeIDEWizardDialog;
import com.intellij.ide.customize.CustomizeIDEWizardStepsProvider;
import com.intellij.ide.gdpr.EndUserAgreement;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.ide.ui.laf.IntelliJLaf;
import com.intellij.jna.JnaLoader;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.ConfigImportHelper;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.ShutDownTracker;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.win32.IdeaWin32;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.impl.X11UiUtil;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.IconManager;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.PlatformUtils;
import com.intellij.util.SystemProperties;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.EdtInvocationManager;
import com.intellij.util.ui.StartupUiUtil;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.io.BuiltInServer;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.intellij.diagnostic.LoadingPhase.LAF_INITIALIZED;
import static java.nio.file.attribute.PosixFilePermission.*;

public final class StartupUtil {
  public static final String FORCE_PLUGIN_UPDATES = "idea.force.plugin.updates";
  public static final String IDEA_CLASS_BEFORE_APPLICATION_PROPERTY = "idea.class.before.app";

  @SuppressWarnings("SpellCheckingInspection") private static final String MAGIC_MAC_PATH = "/AppTranslocation/";

  private static SocketLock ourSocketLock;
  private static final AtomicBoolean ourSystemPatched = new AtomicBoolean();

  private StartupUtil() { }

  /* called by the app after startup */
  public static synchronized void addExternalInstanceListener(@Nullable Function<List<String>, Future<CliResult>> processor) {
    if (ourSocketLock != null) {
      ourSocketLock.setCommandProcessor(processor);
    }
  }

  // used externally by TeamCity plugin (as TeamCity cannot use modern API to support old IDE versions)
  @SuppressWarnings("MissingDeprecatedAnnotation")
  @Deprecated
  public static synchronized @Nullable BuiltInServer getServer() {
    return ourSocketLock == null ? null : ourSocketLock.getServer();
  }

  @NotNull
  public static synchronized CompletableFuture<BuiltInServer> getServerFuture() {
    CompletableFuture<BuiltInServer> serverFuture = ourSocketLock == null ? null : ourSocketLock.getServerFuture();
    return serverFuture == null ? CompletableFuture.completedFuture(null) : serverFuture;
  }

  public interface AppStarter {
    /* called from IDE init thread */
    void start(@NotNull List<String> args, @NotNull CompletionStage<?> initUiTask);

    /* called from IDE init thread */
    default void beforeImportConfigs() {}

    /* called from EDT */
    default void beforeStartupWizard() {}

    /* called from EDT */
    default void startupWizardFinished(@NotNull CustomizeIDEWizardStepsProvider provider) {}

    /* called from IDE init thread */
    default void importFinished(@NotNull Path newConfigDir) {}

    /* called from EDT */
    default int customizeIdeWizardDialog(@NotNull List<AbstractCustomizeWizardStep> steps) {
      return -1;
    }
  }

  private static void runPreAppClass(@NotNull Logger log) {
    String classBeforeAppProperty = System.getProperty(IDEA_CLASS_BEFORE_APPLICATION_PROPERTY);
    if (classBeforeAppProperty != null) {
      try {
        Class<?> clazz = Class.forName(classBeforeAppProperty);
        Method invokeMethod = clazz.getDeclaredMethod("invoke");
        invokeMethod.invoke(null);
      }
      catch (Exception ex) {
        log.error("Failed pre-app class init for class " + classBeforeAppProperty, ex);
      }
    }
  }

  public static void prepareApp(@NotNull String[] args, @NotNull String mainClass) throws Exception {
    LoadingPhase.setStrictMode();

    Activity activity = StartUpMeasurer.startMainActivity("ForkJoin CommonPool configuration");
    IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(Main.isHeadless(args));

    activity = activity.endAndStart("main class loading scheduling");
    ExecutorService executorService = AppExecutorUtil.getAppExecutorService();

    Future<Class<AppStarter>> mainStartFuture = executorService.submit(() -> {
      Activity subActivity = StartUpMeasurer.startActivity("main class loading");
      @SuppressWarnings("unchecked")
      Class<AppStarter> aClass = (Class<AppStarter>)Class.forName(mainClass);
      subActivity.end();
      return aClass;
    });

    activity = activity.endAndStart("log4j configuration");
    configureLog4j();

    activity = activity.endAndStart("LaF init scheduling");
    CompletableFuture<?> initUiTask = scheduleInitUi(args, executorService);
    activity.end();

    if (!checkJdkVersion()) {
      System.exit(Main.JDK_CHECK_FAILED);
    }

    activity = StartUpMeasurer.startMainActivity("config path computing");
    String configPath = PathManager.getConfigPath();
    activity = activity.endAndStart("config path existence check");

    // this check must be performed before system directories are locked
    boolean configImportNeeded = !Main.isHeadless() && !Files.exists(Paths.get(configPath));

    activity = activity.endAndStart("system dirs checking");
    // note: uses config directory
    if (!checkSystemDirs()) {
      System.exit(Main.DIR_CHECK_FAILED);
    }
    activity = activity.endAndStart("system dirs locking");
    lockSystemDirs(args);
    activity = activity.endAndStart("file logger configuration");
    // log initialization should happen only after locking the system directory
    Logger log = setupLogger();
    activity.end();

    executorService.execute(() -> {
      ApplicationInfo appInfo = ApplicationInfoImpl.getShadowInstance();
      Activity subActivity = StartUpMeasurer.startActivity("essential IDE info logging");
      logEssentialInfoAboutIde(log, appInfo);
      subActivity.end();
    });

    Future<?> extraTaskFuture = executorService.submit(() -> {
      Activity subActivity = StartUpMeasurer.startActivity("system libs setup");
      setupSystemLibraries();
      subActivity = subActivity.endAndStart("process env fixing");
      fixProcessEnvironment(log);
      subActivity.end();
    });

    if (!configImportNeeded) {
      installPluginUpdates();
      runPreAppClass(log);
    }

    executorService.execute(() -> loadSystemLibraries(log));

    activity = StartUpMeasurer.startMainActivity("tasks waiting");
    extraTaskFuture.get();

    activity = activity.endAndStart("main class loading waiting");
    Class<AppStarter> aClass = mainStartFuture.get();
    activity.end();

    startApp(args, initUiTask, log, configImportNeeded, aClass.newInstance());
  }

  private static void startApp(@NotNull String[] args,
                               @NotNull CompletableFuture<?> initUiTask,
                               @NotNull Logger log,
                               boolean configImportNeeded,
                               @NotNull AppStarter appStarter) throws Exception {
    if (!Main.isHeadless()) {
      Activity activity = StartUpMeasurer.startMainActivity("config importing");

      if (configImportNeeded) {
        appStarter.beforeImportConfigs();
        Path newConfigDir = Paths.get(PathManager.getConfigPath());
        runInEdtAndWait(log, () -> ConfigImportHelper.importConfigsTo(newConfigDir, log), initUiTask);
        appStarter.importFinished(newConfigDir);
      }

      showUserAgreementAndConsentsIfNeeded(log, initUiTask);

      if (configImportNeeded && !ConfigImportHelper.isConfigImported()) {
        // exception handler is already set by ConfigImportHelper; event queue and icons already initialized as part of old config import
        EventQueue.invokeAndWait(() -> runStartupWizard(appStarter));
      }

      activity.end();
    }

    EdtInvocationManager.executeWithCustomManager(new EdtInvocationManager.SwingEdtInvocationManager() {
      @Override
      public void invokeAndWait(@NotNull Runnable task) {
        runInEdtAndWait(log, task, initUiTask);
      }
    }, () -> appStarter.start(Arrays.asList(args), initUiTask));
  }

  @NotNull
  private static CompletableFuture<?> scheduleInitUi(@NotNull String[] args, @NotNull ExecutorService executor) {
    // mainly call sun.util.logging.PlatformLogger.getLogger - it takes enormous time (up to 500 ms)
    // Before lockDirsAndConfigureLogger can be executed only tasks that do not require log,
    // because we don't want to complicate logging. It is OK, because lockDirsAndConfigureLogger is not so heavy-weight as UI tasks.
    CompletableFuture<Void> future = new CompletableFuture<>();
    executor.execute(() -> {
      try {
        checkHiDPISettings();

        //noinspection SpellCheckingInspection
        System.setProperty("sun.awt.noerasebackground", "true");
        if (System.getProperty("com.jetbrains.suppressWindowRaise") == null) {
          System.setProperty("com.jetbrains.suppressWindowRaise", "true");
        }

        EventQueue.invokeLater(() -> {
          try {
            // it is required even if headless because some tests creates configurable, so, our LaF is expected
            StartupUiUtil.initDefaultLaF();
          }
          catch (Throwable e) {
            future.completeExceptionally(e);
            return;
          }

          future.complete(null);
          LoadingPhase.setCurrentPhase(LAF_INITIALIZED);

          if (Main.isHeadless()) {
            return;
          }

          // UIUtil.initDefaultLaF must be called before this call (required for getSystemFontData(), and getSystemFontData() can be called to compute scale also)
          Activity activity = StartUpMeasurer.startActivity("system font data initialization");
          JBUIScale.getSystemFontData();

          activity = activity.endAndStart("init JBUIScale");
          JBUIScale.scale(1f);

          Activity prepareSplashActivity = activity.endAndStart("splash preparation");
          EventQueue.invokeLater(() -> {
            SplashManager.show(args);
            prepareSplashActivity.end();
          });

          // may be expensive (~200 ms), so configure only after showing the splash and as invokeLater (to allow other queued events to be executed)
          EventQueue.invokeLater(() -> StartupUiUtil.configureHtmlKitStylesheet());
        });
      }
      catch (Throwable e) {
        future.completeExceptionally(e);
      }
    });

    if (!Main.isHeadless()) {
      // do not wait, approach like AtomicNotNullLazyValue is used under the hood
      future.thenRunAsync(() -> {
        updateFrameClassAndWindowIcon();
      }, executor);
    }
    return future;
  }

  private static void updateFrameClassAndWindowIcon() {
    Activity activity = StartUpMeasurer.startActivity("frame class updating");
    AppUIUtil.updateFrameClass(Toolkit.getDefaultToolkit());

    activity = activity.endAndStart("update window icon");
    // updateWindowIcon should be after UIUtil.initSystemFontData because uses computed system font data for scale context
    if (!PluginManagerCore.isRunningFromSources() && !AppUIUtil.isWindowIconAlreadyExternallySet()) {
      // most of the time consumed to load SVG - so, can be done in parallel
      AppUIUtil.updateWindowIcon(JOptionPane.getRootFrame());
    }
    activity.end();
  }

  private static void configureLog4j() {
    Activity activity = StartUpMeasurer.startMainActivity("console logger configuration");
    // avoiding "log4j:WARN No appenders could be found"
    System.setProperty("log4j.defaultInitOverride", "true");
    org.apache.log4j.Logger root = org.apache.log4j.Logger.getRootLogger();
    if (!root.getAllAppenders().hasMoreElements()) {
      root.setLevel(Level.WARN);
      root.addAppender(new ConsoleAppender(new PatternLayout(PatternLayout.DEFAULT_CONVERSION_PATTERN)));
    }
    activity.end();
  }

  private static boolean checkJdkVersion() {
    if ("true".equals(System.getProperty("idea.jre.check"))) {
      try {
        Class.forName("com.sun.jdi.Field", false, StartupUtil.class.getClassLoader());  // trying to find a JDK class
      }
      catch (ClassNotFoundException | LinkageError e) {
        String message = "Cannot load a JDK class: " + e.getMessage() + "\nPlease ensure you run the IDE on JDK rather than JRE.";
        Main.showMessage("JDK Required", message, true);
        return false;
      }
    }

    if ("true".equals(System.getProperty("idea.64bit.check"))) {
      if (PlatformUtils.isCidr() && !SystemInfo.is64Bit) {
        String message = "32-bit JVM is not supported. Please use a 64-bit version.";
        Main.showMessage("Unsupported JVM", message, true);
        return false;
      }
    }

    return true;
  }

  @TestOnly
  public static void test_checkHiDPISettings() {
    checkHiDPISettings();
  }

  private static void checkHiDPISettings() {
    if (!SystemProperties.getBooleanProperty("hidpi", true)) {
      // suppress JRE-HiDPI mode
      System.setProperty("sun.java2d.uiScale.enabled", "false");
    }
  }

  private static synchronized boolean checkSystemDirs() {
    String configPath = PathManager.getConfigPath();
    PathManager.ensureConfigFolderExists();
    if (!checkDirectory(configPath, "Config", PathManager.PROPERTY_CONFIG_PATH, true, true, false)) {
      return false;
    }

    String systemPath = PathManager.getSystemPath();
    if (!checkDirectory(systemPath, "System", PathManager.PROPERTY_SYSTEM_PATH, true, true, false)) {
      return false;
    }

    if (FileUtil.pathsEqual(configPath, systemPath)) {
      String message = "Config and system paths seem to be equal.\n\n" +
                       "If you have modified '" + PathManager.PROPERTY_CONFIG_PATH + "' or '" + PathManager.PROPERTY_SYSTEM_PATH + "' properties,\n" +
                       "please make sure they point to different directories, otherwise please re-install the IDE.";
      Main.showMessage("Invalid Config or System Path", message, true);
      return false;
    }

    String logPath = PathManager.getLogPath(), tempPath = PathManager.getTempPath();
    return checkDirectory(logPath, "Log", PathManager.PROPERTY_LOG_PATH, !FileUtil.isAncestor(systemPath, logPath, true), false, false) &&
           checkDirectory(tempPath, "Temp", PathManager.PROPERTY_SYSTEM_PATH, !FileUtil.isAncestor(systemPath, tempPath, true), false, SystemInfo.isXWindow);
  }

  private static boolean checkDirectory(String path, String kind, String property, boolean checkWrite, boolean checkLock, boolean checkExec) {
    String problem = null, reason = null;
    Path tempFile = null;

    try {
      problem = "cannot create the directory";
      reason = "path is incorrect";
      Path directory = Paths.get(path);

      if (!Files.isDirectory(directory)) {
        problem = "cannot create the directory";
        reason = "parent directory is read-only or the user lacks necessary permissions";
        Files.createDirectories(directory);
      }

      if (checkWrite || checkLock || checkExec) {
        problem = "cannot create a temporary file in the directory";
        reason = "the directory is read-only or the user lacks necessary permissions";
        tempFile = directory.resolve("ij" + new Random().nextInt(Integer.MAX_VALUE) + ".tmp");
        OpenOption[] options = {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
        Files.write(tempFile, "#!/bin/sh\nexit 0".getBytes(StandardCharsets.UTF_8), options);

        if (checkLock) {
          problem = "cannot create a lock file in the directory";
          reason = "the directory is located on a network disk";
          try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("File is locked");
          }
        }
        else if (checkExec) {
          problem = "cannot execute a test script in the directory";
          reason = "the partition is mounted with 'no exec' option";
          Files.getFileAttributeView(tempFile, PosixFileAttributeView.class).setPermissions(EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
          int ec = new ProcessBuilder(tempFile.toAbsolutePath().toString()).start().waitFor();
          if (ec != 0) {
            throw new IOException("Unexpected exit value: " + ec);
          }
        }
      }

      return true;
    }
    catch (Exception e) {
      String title = "Invalid " + kind + " Directory";
      String advice = SystemInfo.isMac && PathManager.getSystemPath().contains(MAGIC_MAC_PATH)
                      ? "The application seems to be trans-located by macOS and cannot be used in this state.\n" +
                        "Please use Finder to move it to another location."
                      : "If you have modified the '" + property + "' property, please make sure it is correct,\n" +
                        "otherwise, please re-install the IDE.";
      String message = "The IDE " + problem + ".\nPossible reason: " + reason + ".\n\n" + advice +
                       "\n\n-----\nLocation: " + path + "\n" + e.getClass().getName() + ": " + e.getMessage();
      Main.showMessage(title, message, true);
      return false;
    }
    finally {
      if (tempFile != null) {
        try { Files.deleteIfExists(tempFile); }
        catch (Exception ignored) { }
      }
    }
  }

  private static synchronized void lockSystemDirs(String[] args) throws Exception {
    if (ourSocketLock != null) throw new AssertionError();
    ourSocketLock = new SocketLock(PathManager.getConfigPath(), PathManager.getSystemPath());

    Pair<SocketLock.ActivationStatus, CliResult> status = ourSocketLock.lockAndTryActivate(args);
    switch (status.first) {
      case NO_INSTANCE: {
        ShutDownTracker.getInstance().registerShutdownTask(() -> {
          //noinspection SynchronizeOnThis
          synchronized (StartupUtil.class) {
            ourSocketLock.dispose();
            ourSocketLock = null;
          }
        });
        break;
      }

      case ACTIVATED: {
        CliResult result = status.second;
        String message = result.message;
        if (message == null) message = "Already running";
        //noinspection UseOfSystemOutOrSystemErr
        System.out.println(message);
        System.exit(result.exitCode);
      }

      case CANNOT_ACTIVATE: {
        String message = "Only one instance of " + ApplicationNamesInfo.getInstance().getProductName() + " can be run at a time.";
        Main.showMessage("Too Many Instances", message, true);
        System.exit(Main.INSTANCE_CHECK_FAILED);
      }
    }
  }

  @NotNull
  private static Logger setupLogger() {
    Logger.setFactory(new LoggerFactory());
    Logger log = Logger.getInstance("#com.intellij.idea.Main");
    log.info("------------------------------------------------------ IDE STARTED ------------------------------------------------------");
    ShutDownTracker.getInstance().registerShutdownTask(() -> {
      log.info("------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------");
    });
    return log;
  }

  private static void fixProcessEnvironment(Logger log) {
    if (!Main.isCommandLine()) {
      System.setProperty("__idea.mac.env.lock", "unlocked");
    }
    boolean envReady = EnvironmentUtil.isEnvironmentReady();  // trigger environment loading
    if (!envReady) {
      log.info("initializing environment");
    }
  }

  @SuppressWarnings("SpellCheckingInspection")
  private static void setupSystemLibraries() {
    String ideTempPath = PathManager.getTempPath();

    if (System.getProperty("jna.tmpdir") == null) {
      System.setProperty("jna.tmpdir", ideTempPath);  // to avoid collisions and work around no-exec /tmp
    }
    if (System.getProperty("jna.nosys") == null) {
      System.setProperty("jna.nosys", "true");  // prefer bundled JNA dispatcher lib
    }

    if (SystemInfo.isWindows && System.getProperty("winp.folder.preferred") == null) {
      System.setProperty("winp.folder.preferred", ideTempPath);
    }

    if (System.getProperty("pty4j.tmpdir") == null) {
      System.setProperty("pty4j.tmpdir", ideTempPath);
    }
    if (System.getProperty("pty4j.preferred.native.folder") == null) {
      System.setProperty("pty4j.preferred.native.folder", new File(PathManager.getLibPath(), "pty4j-native").getAbsolutePath());
    }
  }

  private static void loadSystemLibraries(Logger log) {
    Activity activity = StartUpMeasurer.startActivity("system libs loading");

    JnaLoader.load(log);

    //noinspection ResultOfMethodCallIgnored
    IdeaWin32.isAvailable();

    activity.end();
  }

  private static void logEssentialInfoAboutIde(@NotNull Logger log, @NotNull ApplicationInfo appInfo) {
    ApplicationNamesInfo namesInfo = ApplicationNamesInfo.getInstance();
    String buildDate = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(appInfo.getBuildDate().getTime());
    log.info("IDE: " + namesInfo.getFullProductName() + " (build #" + appInfo.getBuild().asString() + ", " + buildDate + ")");
    log.info("OS: " + SystemInfo.OS_NAME + " (" + SystemInfo.OS_VERSION + ", " + SystemInfo.OS_ARCH + ")");
    log.info("JRE: " + System.getProperty("java.runtime.version", "-") + " (" + System.getProperty("java.vendor", "-") + ")");
    log.info("JVM: " + System.getProperty("java.vm.version", "-") + " (" + System.getProperty("java.vm.name", "-") + ")");

    List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (arguments != null) {
      log.info("JVM Args: " + StringUtil.join(arguments, " "));
    }

    String extDirs = System.getProperty("java.ext.dirs");
    if (extDirs != null) {
      for (String dir : StringUtil.split(extDirs, File.pathSeparator)) {
        String[] content = new File(dir).list();
        if (content != null && content.length > 0) {
          log.info("ext: " + dir + ": " + Arrays.toString(content));
        }
      }
    }

    log.info("charsets: JNU=" + System.getProperty("sun.jnu.encoding") + " file=" + System.getProperty("file.encoding"));
  }

  private static void installPluginUpdates() {
    if (!Main.isCommandLine() || Boolean.getBoolean(FORCE_PLUGIN_UPDATES)) {
      try {
        StartupActionScriptManager.executeActionScript();
      }
      catch (IOException e) {
        String message =
          "The IDE failed to install some plugins.\n\n" +
          "Most probably, this happened because of a change in a serialization format.\n" +
          "Please try again, and if the problem persists, please report it\n" +
          "to http://jb.gg/ide/critical-startup-errors" +
          "\n\nThe cause: " + e.getMessage();
        Main.showMessage("Plugin Installation Error", message, false);
      }
    }
  }

  private static void runStartupWizard(@NotNull AppStarter appStarter) {
    String stepsProviderName = ApplicationInfoImpl.getShadowInstance().getCustomizeIDEWizardStepsProvider();
    if (stepsProviderName == null) {
      return;
    }

    CustomizeIDEWizardStepsProvider provider;
    try {
      Class<?> providerClass = Class.forName(stepsProviderName);
      provider = (CustomizeIDEWizardStepsProvider)providerClass.newInstance();
    }
    catch (Throwable e) {
      Main.showMessage("Configuration Wizard Failed", e);
      return;
    }

    appStarter.beforeStartupWizard();
    new CustomizeIDEWizardDialog(provider, appStarter, true, false).showIfNeeded();

    PluginManagerCore.invalidatePlugins();
    appStarter.startupWizardFinished(provider);
  }

  // must be called from EDT
  public static boolean patchSystem(@NotNull Logger log) {
    if (!ourSystemPatched.compareAndSet(false, true)) {
      return false;
    }

    Activity activity = StartUpMeasurer.startActivity("event queue replacing");
    replaceSystemEventQueue(log);
    if (!Main.isHeadless()) {
      patchSystemForUi(log);
    }
    activity.end();
    return true;
  }

  @ApiStatus.Internal
  public static void replaceSystemEventQueue(@NotNull Logger log) {
    log.info("CPU cores: " + Runtime.getRuntime().availableProcessors() +
             "; ForkJoinPool.commonPool: " + ForkJoinPool.commonPool() +
             "; factory: " + ForkJoinPool.commonPool().getFactory());

    // replaces system event queue
    //noinspection ResultOfMethodCallIgnored
    IdeEventQueue.getInstance();
  }

  private static void patchSystemForUi(@NotNull Logger log) {
    // Using custom RepaintManager disables BufferStrategyPaintManager (and so, true double buffering)
    // because the only non-private constructor forces RepaintManager.BUFFER_STRATEGY_TYPE = BUFFER_STRATEGY_SPECIFIED_OFF.
    //
    // At the same time, http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6209673 seems to be now fixed.
    //
    // This matters only if {@code swing.bufferPerWindow = true} and we don't invoke JComponent.getGraphics() directly.
    //
    // True double buffering is needed to eliminate tearing on blit-accelerated scrolling and to restore
    // frame buffer content without the usual repainting, even when the EDT is blocked.
    if (Patches.REPAINT_MANAGER_LEAK) {
      RepaintManager.setCurrentManager(new IdeRepaintManager());
    }

    if (SystemInfo.isXWindow) {
      String wmName = X11UiUtil.getWmName();
      log.info("WM detected: " + wmName);
      if (wmName != null) {
        X11UiUtil.patchDetectedWm(wmName);
      }
    }

    IconManager.activate();
  }

  private static void showUserAgreementAndConsentsIfNeeded(@NotNull Logger log, @NotNull CompletableFuture<?> initUiTask) {
    if (!ApplicationInfoImpl.getShadowInstance().isVendorJetBrains()) {
      return;
    }

    EndUserAgreement.updateCachedContentToLatestBundledVersion();
    EndUserAgreement.Document agreement = EndUserAgreement.getLatestDocument();
    if (!agreement.isAccepted()) {
      // todo: does not seem to request focus when shown
      runInEdtAndWait(log, () -> AppUIUtil.showEndUserAgreementText(agreement.getText(), agreement.isPrivacyPolicy()), initUiTask);
      EndUserAgreement.setAccepted(agreement);
    }

    AppUIUtil.showConsentsAgreementIfNeeded(command -> runInEdtAndWait(log, command, initUiTask));
  }

  private static void runInEdtAndWait(@NotNull Logger log, @NotNull Runnable runnable, @NotNull CompletableFuture<?> initUiTask) {
    try {
      initUiTask.get();

      if (!ourSystemPatched.get()) {
        EventQueue.invokeAndWait(() -> {
          if (!patchSystem(log)) {
            return;
          }

          try {
            UIManager.setLookAndFeel(IntelliJLaf.class.getName());
            IconManager.activate();
            // todo investigate why in test mode dummy icon manager is not suitable
            IconLoader.activate();
            // we don't set AppUIUtil.updateForDarcula(false) because light is default
          }
          catch (Exception ignore) { }
        });
      }

      // this invokeAndWait() call is needed to place on a freshly minted IdeEventQueue instance
      EventQueue.invokeAndWait(runnable);
    }
    catch (InterruptedException | InvocationTargetException | ExecutionException e) {
      log.warn(e);
    }
  }
}