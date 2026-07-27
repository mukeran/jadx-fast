package jadx.cli;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.analysis.callgraph.JadxCallGraph;
import jadx.analysis.callgraph.api.ICallGraph;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.impl.AnnotatedCodeWriter;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.impl.SimpleCodeWriter;
import jadx.api.usage.impl.EmptyUsageInfoCache;
import jadx.cli.LogHelper.LogLevelEnum;
import jadx.cli.config.JadxConfigAdapter;
import jadx.cli.plugins.JadxFilesGetter;
import jadx.core.utils.exceptions.JadxArgsValidateException;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.plugins.tools.JadxExternalPluginsLoader;

public class JadxCLI {
	private static final Logger LOG = LoggerFactory.getLogger(JadxCLI.class);
	private static final long JVM_PRE_MAIN_NANOS = Math.max(
			0,
			System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime()) * 1_000_000L;

	public static void main(String[] args) {
		int result = 1;
		try {
			result = execute(args);
		} finally {
			System.exit(result);
		}
	}

	public static int execute(String[] args) {
		return execute(args, null);
	}

	public static int execute(String[] args, @Nullable Consumer<JadxArgs> argsMod) {
		long cliSetupStart = System.nanoTime();
		try {
			JadxCLIArgs cliArgs = JadxCLIArgs.processArgs(args,
					new JadxCLIArgs(),
					new JadxConfigAdapter<>(JadxCLIArgs.class, "cli"));
			if (cliArgs == null) {
				return 0;
			}
			JadxArgs jadxArgs = buildArgs(cliArgs);
			if (argsMod != null) {
				argsMod.accept(jadxArgs);
			}
			long cliSetupNanos = System.nanoTime() - cliSetupStart;
			return runSave(jadxArgs, cliArgs, cliSetupNanos);
		} catch (JadxArgsValidateException e) {
			LOG.error("Incorrect arguments: {}", e.getMessage());
			return 1;
		} catch (Throwable e) {
			LOG.error("Process error:", e);
			return 1;
		}
	}

	private static JadxArgs buildArgs(JadxCLIArgs cliArgs) {
		JadxArgs jadxArgs = cliArgs.toJadxArgs();
		if ((cliArgs.isSingleClassFast() || cliArgs.isSingleClassDaemon()) && isDexInput(jadxArgs)) {
			jadxArgs.getDisabledPlugins().add("java-input");
			jadxArgs.getDisabledPlugins().add("java-convert");
		}
		if (cliArgs.isSingleClassDaemon()) {
			jadxArgs.setSkipResources(true);
		}
		jadxArgs.setCodeCache(new NoOpCodeCache());
		jadxArgs.setUsageInfoCache(new EmptyUsageInfoCache());
		jadxArgs.setPluginLoader(new JadxExternalPluginsLoader());
		jadxArgs.setFilesGetter(JadxFilesGetter.INSTANCE);
		initCodeWriterProvider(jadxArgs);
		JadxAppCommon.applyEnvVars(jadxArgs);
		return jadxArgs;
	}

	private static boolean isDexInput(JadxArgs jadxArgs) {
		return !jadxArgs.getInputFiles().isEmpty()
				&& jadxArgs.getInputFiles().stream().allMatch(file -> {
					String name = file.getName().toLowerCase(Locale.ROOT);
					return name.endsWith(".apk") || name.endsWith(".dex");
				});
	}

	private static int runSave(JadxArgs jadxArgs, JadxCLIArgs cliArgs, long cliSetupNanos) {
		if (cliArgs.isSingleClassDaemon()) {
			return SingleClassDaemon.run(jadxArgs, cliArgs, JVM_PRE_MAIN_NANOS, cliSetupNanos);
		}
		try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
			if (cliArgs.isSingleClassFast()) {
				if (cliArgs.getSingleClass() == null) {
					throw new JadxArgsValidateException("--single-class-fast requires --single-class");
				}
				jadx.loadSingleClass(cliArgs.getSingleClass());
			} else {
				jadx.load();
			}
			if (checkForErrors(jadx)) {
				return 2;
			}
			writeCallGraph(jadx, cliArgs);
			SingleClassMode.ProcessResult singleClassResult = SingleClassMode.processWithResult(jadx, cliArgs);
			if (singleClassResult == null) {
				save(jadx);
			} else if (cliArgs.isSingleClassTimings()) {
				printSingleClassTimings(jadx, singleClassResult, cliSetupNanos);
			}
			int errorsCount = jadx.getErrorsCount();
			if (errorsCount != 0) {
				jadx.printErrorsReport();
				LOG.error("finished with errors, count: {}", errorsCount);
				return 3;
			}
			LOG.info("done");
			return 0;
		}
	}

	private static void printSingleClassTimings(
			JadxDecompiler jadx,
			SingleClassMode.ProcessResult result,
			long cliSetupNanos) {
		Map<String, Long> timings = new LinkedHashMap<>();
		timings.put("jvm-pre-main", JVM_PRE_MAIN_NANOS);
		timings.put("cli-setup", cliSetupNanos);
		jadx.getSingleClassPrepareTimingsNanos()
				.forEach((name, nanos) -> timings.put("prepare." + name, nanos));
		jadx.getSingleClassRequestTimingsNanos()
				.forEach((name, nanos) -> timings.put("request." + name, nanos));
		timings.put("decompile", result.getDecompileNanos());
		timings.put("save", result.getSaveNanos());
		StringBuilder report = new StringBuilder("single-class timings (ms):");
		timings.forEach((name, nanos) -> report
				.append(System.lineSeparator())
				.append("  ")
				.append(name)
				.append(": ")
				.append(String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)));
		LOG.info(report.toString());
	}

	private static void initCodeWriterProvider(JadxArgs jadxArgs) {
		switch (jadxArgs.getOutputFormat()) {
			case JAVA:
				jadxArgs.setCodeWriterProvider(SimpleCodeWriter::new);
				break;
			case JSON:
				// needed for code offsets and source lines
				jadxArgs.setCodeWriterProvider(AnnotatedCodeWriter::new);
				break;
		}
	}

	private static boolean checkForErrors(JadxDecompiler jadx) {
		if (jadx.getRoot().getClasses().isEmpty()) {
			if (jadx.getArgs().isSkipResources()) {
				LOG.error("Load failed! No classes for decompile!");
				return true;
			}
			if (!jadx.getArgs().isSkipSources()) {
				LOG.warn("No classes to decompile; decoding resources only");
				jadx.getArgs().setSkipSources(true);
			}
		}
		int errorsCount = jadx.getErrorsCount();
		if (errorsCount > 0) {
			LOG.error("Loading finished with errors! Count: {}", errorsCount);
			// continue processing
		}
		return false;
	}

	private static void save(JadxDecompiler jadx) {
		if (LogHelper.getLogLevel() == LogLevelEnum.QUIET) {
			jadx.save();
		} else {
			LOG.info("processing ...");
			jadx.save(500, (done, total) -> {
				int progress = (int) (done * 100.0 / total);
				System.out.printf("INFO  - progress: %d of %d (%d%%)\r", done, total, progress);
			});
			// dumb line clear :)
			System.out.print("                                                             \r");
		}
	}

	private static void writeCallGraph(JadxDecompiler jadx, JadxCLIArgs cliArgs) {
		JadxCLIArgs.CallGraphSaveMode mode = cliArgs.callGraphSaveMode;
		if (mode == null || mode == JadxCLIArgs.CallGraphSaveMode.NONE) {
			return;
		}
		Path outPath = jadx.getArgs().getOutDir().toPath();
		ICallGraph callGraph = JadxCallGraph.builder(jadx)
				.resolvedOnly(true)
				.build();
		Path cgPath;
		switch (mode) {
			case JSON:
				cgPath = outPath.resolve("callgraph.json");
				callGraph.writeJson(cgPath);
				break;
			case DOT:
				cgPath = outPath.resolve("callgraph.dot");
				callGraph.writeDot(cgPath);
				break;
			default:
				throw new JadxRuntimeException("Unexpected call graph save mode: " + mode);
		}
		LOG.info("Call graph saved: {}", cgPath.toAbsolutePath());
	}
}
