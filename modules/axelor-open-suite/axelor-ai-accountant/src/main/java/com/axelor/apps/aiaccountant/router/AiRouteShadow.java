package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.google.inject.Singleton;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the Router Agent beside the existing router and writes down where they disagree.
 *
 * <h2>Shadow means the turn cannot tell</h2>
 *
 * The verdict is never read by anything. It does not authorise a write, does not select a target,
 * does not choose a playbook and does not reach the model. The only thing it produces is a log
 * line. That is the point of the phase: the router has to be measured against real traffic before
 * it is trusted with any of those, and there is no way to measure it on real traffic except by
 * running it on real traffic.
 *
 * <p>Because it decides nothing, its cost has to be nothing too. The call happens on a background
 * thread with a small bounded queue, so a slow or hanging router adds no latency to the turn and
 * cannot pile up: past the queue the work is dropped and counted. A user waiting for an answer must
 * never wait on a component whose output is discarded.
 *
 * <h2>Built here, sent there</h2>
 *
 * The request text is assembled on the caller's thread by {@link #capture}, where the
 * request-scoped state actually exists — the Shiro subject, the company scope, the page context —
 * and, crucially, before the turn has opened or changed a workflow. The background thread receives
 * a finished string and does nothing but HTTP, parse and log. A background thread that tried to
 * read the active company would find none, and the router would be evaluated on context the real
 * path never had.
 *
 * <h2>The line</h2>
 *
 * Emitted on the metrics logger, so it lands in the same host-mounted file as {@code ai.turn} and
 * survives the container:
 *
 * <pre>
 * ai.router mode=shadow outcome=route latency_ms=740 agreement=4/5 differs=task
 *           domain=SUPPLIER_INVOICE|SUPPLIER_INVOICE task=REVIEW…|REPAIR… goal=-|- …
 * </pre>
 *
 * <p>Adapter first, router second, in every pair. It carries enum names, counts and durations —
 * never the message, never a record, never a supplier — which is the property that lets it share a
 * file with the other metric lines.
 */
@Singleton
public class AiRouteShadow {

  private static final Logger LOG = LoggerFactory.getLogger(AiRouteShadow.class);

  /**
   * Room for a few turns' worth of shadow work and no more.
   *
   * <p>Deep enough that ordinary bursts are absorbed, shallow enough that a router which has
   * stopped answering is noticed as dropped observations within seconds rather than accumulating a
   * backlog of stale ones. A dropped observation costs a sample; a queued one would cost memory and
   * still be worthless by the time it ran.
   */
  private static final int QUEUE_CAPACITY = 8;

  private final AiAccountantConfig config;
  private final AiRouterService router;
  private final ThreadPoolExecutor executor;
  private final AtomicLong dropped = new AtomicLong();

  @Inject
  public AiRouteShadow(AiAccountantConfig config, AiRouterService router) {
    this.config = config;
    this.router = router;
    this.executor =
        new ThreadPoolExecutor(
            0,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
              Thread t = new Thread(r, "ai-router-shadow");
              // Daemon: an observation must never hold up a shutdown, and losing the last one
              // costs a sample.
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Whether anything will be observed this turn.
   *
   * <p>Asked before the pre-turn workflow is read, so that a switched-off router costs the turn
   * nothing at all — not even the extra store lookup that capturing a snapshot would need. With the
   * router off, which is the default, the turn runs exactly the reads it ran before this phase.
   */
  public boolean isObserving() {
    try {
      return config.isRouterShadowMode() && router.isAvailable();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the router configuration: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Freezes what the router will be asked, from the state of the world before this message changed
   * any of it.
   *
   * <h2>Why this is a separate step, and why it happens first</h2>
   *
   * A comparison is only a comparison while both sides answer the same question. The first version
   * built the router's input from the workflow that {@code open} had just produced — a workflow
   * whose task, goal, scope and explicitness are the adapter's classification of this very message.
   * The router was being handed the answer and marked on how closely it echoed it, and any
   * agreement measured that way means nothing at all.
   *
   * <p>So the input is captured here, before the turn opens or replaces anything: the original
   * message, the page context as it arrived, the active company, and the workflow as it stood at
   * the start of the turn. The adapter classifies from exactly the same three things.
   *
   * <p>The result is a string, which is what makes the snapshot immutable in the sense that matters
   * — a continuation hands the live workflow object to the turn to mutate, and by then this text
   * has already been rendered and cannot follow it. Returns null when the router is off, which the
   * caller passes on without needing to know why.
   *
   * <p>Never throws: instrumentation that can fail a turn is worse than no instrumentation.
   */
  public String capture(String message, PageContext page, AiWorkflow preTurn, Long companyId) {
    try {
      if (!isObserving()) {
        return null;
      }
      // Built on the caller's thread, while the request-scoped state still exists.
      return AiRouterContract.request(message, page, preTurn, companyId);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not capture the shadow router input: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Sends a captured input to the router and logs how its answer compares to the adapter's.
   *
   * <p>Everything after this point is off the turn's thread and out of its way. The adapter route
   * arrives already decided and is used by the turn regardless; this only writes down whether the
   * router would have said the same thing.
   */
  public void observe(String capturedInput, AiRoute adapterRoute) {
    try {
      if (capturedInput == null || adapterRoute == null) {
        return;
      }
      submit(capturedInput, adapterRoute);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not start the shadow router: {}", e.getMessage());
    }
  }

  protected void submit(String input, AiRoute adapterRoute) {
    try {
      executor.execute(() -> compare(input, adapterRoute));
    } catch (RejectedExecutionException e) {
      long total = dropped.incrementAndGet();
      // Counted rather than silent: a router that is being dropped every turn looks exactly like a
      // router that agrees with the adapter every turn, unless the drops are visible.
      AiRouterLog.emitDropped(AiAccountantConfig.ROUTER_MODE_SHADOW, total);
    }
  }

  protected void compare(String input, AiRoute adapterRoute) {
    try {
      AiRouterVerdict verdict = router.classify(input);
      AiRouterLog.emit(
          AiAccountantConfig.ROUTER_MODE_SHADOW, adapterRoute, verdict, AiRouterLog.USED_ADAPTER);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: shadow router comparison failed: {}", e.getMessage());
    }
  }
}
