package com.axelor.apps.aiaccountant.metrics;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.inject.Beans;
import java.io.File;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the measured turns after the container that measured them is gone.
 *
 * <h2>Why this exists</h2>
 *
 * {@link AiTurnMetrics} writes one line per turn to stdout. A container runtime keeps a container's
 * stdout only for the life of that container, so without a log volume every rebuild destroys the
 * entire sample and tuning the assistant from real data has nothing to reason from. A host-mounted
 * file survives that.
 *
 * <h2>What it attaches to, and what it deliberately does not</h2>
 *
 * A {@link RollingFileAppender} on the {@code com.axelor.ai.metrics} logger and nothing else. That
 * logger carries only {@code ai.turn}, {@code ai.budget} and {@code ai.router} lines — counters,
 * durations, token figures, controlled-vocabulary values and tool <em>names</em> — so the file
 * cannot contain a supplier, an IBAN, an invoice number, a tool argument or a message. That is a
 * property of what the three writers emit, not a filter applied here, so anything else logging to
 * this name would have to keep the same promise.
 *
 * <p>Additivity is left alone, so the stdout line is still written and the container log keeps
 * showing it. The root logger's own appenders are untouched, so ordinary application logs are not
 * duplicated into this file.
 *
 * <h2>Failure policy</h2>
 *
 * Every failure is swallowed with a warning. An unwritable mount is a reason to lose metrics, never
 * a reason to fail boot or to break a chat turn. Installation is idempotent — the appender is
 * looked up by name first — so a repeated startup event cannot double-write each line.
 */
public final class AiMetricsFileLog {

  private static final Logger LOG = LoggerFactory.getLogger(AiMetricsFileLog.class);

  /** The logger both {@code ai.turn} and {@code ai.budget} are emitted on. */
  static final String METRICS_LOGGER = "com.axelor.ai.metrics";

  static final String APPENDER_NAME = "AI_METRICS_FILE";

  /** Just the message and a timestamp: level, thread and logger name would only pad the file. */
  static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %m%n";

  private AiMetricsFileLog() {}

  /** Installs the appender if one is configured and not already present. Never throws. */
  public static void install() {
    try {
      AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
      String path = config.metricsFile();
      if (path.isEmpty()) {
        LOG.info(
            "AI Accountant: metrics are stdout-only (aiaccountant.metrics.file is unset); they will"
                + " be lost when this container is replaced.");
        return;
      }
      install(path, config.metricsMaxFileSize(), config.metricsMaxFiles());
    } catch (RuntimeException | LinkageError e) {
      // LinkageError too: logback is the runtime binding today, but a swapped SLF4J backend must
      // degrade to stdout-only metrics rather than take the application down at boot.
      LOG.warn("AI Accountant: could not install the metrics file log: {}", e.getMessage());
    }
  }

  static void install(String path, String maxFileSize, int maxFiles) {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (!(factory instanceof LoggerContext)) {
      LOG.warn(
          "AI Accountant: metrics file log needs logback; found {}. Metrics stay on stdout.",
          factory.getClass().getName());
      return;
    }
    LoggerContext context = (LoggerContext) factory;
    ch.qos.logback.classic.Logger metrics = context.getLogger(METRICS_LOGGER);

    if (metrics.getAppender(APPENDER_NAME) != null) {
      return; // already installed — a second startup event must not duplicate every line
    }

    File file = new File(path);
    File dir = file.getParentFile();
    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
      LOG.warn(
          "AI Accountant: metrics directory {} does not exist and could not be created; metrics"
              + " stay on stdout.",
          dir);
      return;
    }

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(PATTERN);
    encoder.start();

    RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
    appender.setContext(context);
    appender.setName(APPENDER_NAME);
    appender.setFile(file.getPath());
    appender.setAppend(true);
    appender.setEncoder(encoder);

    // Fixed-window by size rather than by date: the file is tiny and what matters is that a long
    // sample stays in one place, not that it is split per day. maxFiles caps the mount.
    FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
    rolling.setContext(context);
    rolling.setParent(appender);
    rolling.setFileNamePattern(file.getPath() + ".%i");
    rolling.setMinIndex(1);
    rolling.setMaxIndex(Math.max(1, maxFiles));
    rolling.start();
    appender.setRollingPolicy(rolling);

    SizeBasedTriggeringPolicy<ILoggingEvent> trigger = new SizeBasedTriggeringPolicy<>();
    trigger.setContext(context);
    trigger.setMaxFileSize(FileSize.valueOf(maxFileSize));
    trigger.start();
    appender.setTriggeringPolicy(trigger);

    appender.start();
    metrics.addAppender(appender);

    LOG.info(
        "AI Accountant: metrics are also written to {} (roll at {}, {} file(s) kept).",
        file.getPath(),
        maxFileSize,
        Math.max(1, maxFiles));
  }
}
