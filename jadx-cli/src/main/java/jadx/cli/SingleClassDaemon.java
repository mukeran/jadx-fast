package jadx.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;

import ch.qos.logback.classic.Level;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.cli.LogHelper.LogLevelEnum;
import jadx.cli.SingleClassMode.ProcessResult;
import jadx.core.utils.exceptions.JadxArgsValidateException;

public final class SingleClassDaemon {
	private static final Gson GSON = new Gson();

	private SingleClassDaemon() {
	}

	public static int run(JadxArgs jadxArgs, JadxCLIArgs cliArgs, long jvmPreMainNanos, long cliSetupNanos) {
		if (jadxArgs.getInputFiles().isEmpty()) {
			throw new JadxArgsValidateException("--single-class-daemon requires an input file");
		}
		if (cliArgs.getSingleClass() != null) {
			throw new JadxArgsValidateException("--single-class-daemon reads class names from stdin, don't use --single-class");
		}
		LogHelper.setLogLevel(LogLevelEnum.QUIET);
		LogHelper.setLevelForClass(JadxDecompiler.class, Level.OFF);
		LogHelper.setLevelForClass(SingleClassMode.class, Level.OFF);
		try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs);
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
				PrintWriter writer = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
			jadx.prepareSingleClassInput();
			jadx.prepareSingleClassLookup();
			Map<String, Object> ready = response("ready");
			ready.put("pid", ProcessHandle.current().pid());
			ready.put("timings_ms", mergeTimings(
					Map.of("jvm-pre-main", jvmPreMainNanos, "cli-setup", cliSetupNanos),
					prefixTimings("prepare.", jadx.getSingleClassPrepareTimingsNanos())));
			writer.println(GSON.toJson(ready));

			String line;
			while ((line = reader.readLine()) != null) {
				String clsName = line.trim();
				if (clsName.isEmpty()) {
					continue;
				}
				if (clsName.equals("quit") || clsName.equals("exit")) {
					writer.println(GSON.toJson(response("bye")));
					return 0;
				}
				processRequest(jadx, cliArgs, writer, clsName);
			}
			return 0;
		} catch (JadxArgsValidateException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Single class daemon failed", e);
		}
	}

	private static void processRequest(JadxDecompiler jadx, JadxCLIArgs cliArgs, PrintWriter writer, String clsName) {
		long totalStart = System.nanoTime();
		try {
			if (!jadx.reloadSingleClass(clsName)) {
				throw new JadxArgsValidateException("Input class not found: " + clsName);
			}
			cliArgs.singleClass = clsName;
			ProcessResult result = SingleClassMode.processWithResult(jadx, cliArgs);
			if (result == null) {
				throw new JadxArgsValidateException("Single class output is not configured");
			}
			Map<String, Long> outputTimings = new LinkedHashMap<>();
			outputTimings.put("decompile", result.getDecompileNanos());
			outputTimings.put("save", result.getSaveNanos());
			outputTimings.put("request-total", System.nanoTime() - totalStart);

			Map<String, Object> response = response("ok");
			response.put("class", clsName);
			response.put("output", result.getOutput().getAbsolutePath());
			response.put("timings_ms", mergeTimings(
					prefixTimings("request.", jadx.getSingleClassRequestTimingsNanos()),
					outputTimings));
			writer.println(GSON.toJson(response));
		} catch (Exception e) {
			Map<String, Object> response = response("error");
			response.put("class", clsName);
			response.put("message", e.getMessage());
			response.put("request_ms", nanosToMillis(System.nanoTime() - totalStart));
			writer.println(GSON.toJson(response));
		}
	}

	private static Map<String, Object> response(String status) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("status", status);
		return response;
	}

	private static Map<String, Long> prefixTimings(String prefix, Map<String, Long> timings) {
		Map<String, Long> result = new LinkedHashMap<>();
		timings.forEach((name, nanos) -> result.put(prefix + name, nanos));
		return result;
	}

	@SafeVarargs
	private static Map<String, Double> mergeTimings(Map<String, Long>... timingMaps) {
		Map<String, Double> result = new LinkedHashMap<>();
		for (Map<String, Long> timingMap : timingMaps) {
			timingMap.forEach((name, nanos) -> result.put(name, nanosToMillis(nanos)));
		}
		return result;
	}

	private static double nanosToMillis(long nanos) {
		return nanos / 1_000_000.0;
	}
}
