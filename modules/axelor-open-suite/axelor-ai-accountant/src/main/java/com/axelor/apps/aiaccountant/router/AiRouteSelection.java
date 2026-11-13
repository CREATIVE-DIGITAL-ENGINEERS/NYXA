package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.route.AiRoute;

/**
 * The route the turn should run on, and which source decided it.
 *
 * <h2>Why there is no clarification outcome here</h2>
 *
 * There used to be one: the router could return a question that ended the turn before the main
 * agent ever ran. It cannot any more. The router selects a route — it never sees enough to ask the
 * user anything sensible, and it has no way to say it either way — so every one of its answers,
 * including a genuine {@code CLARIFICATION} or a {@code CONTINUE} that fails Java's own target
 * check, resolves to a route: the router's when it is usable, {@code AiRouteAdapter}'s otherwise.
 * Deciding whether the user needs to be asked anything is the main agent's job now, made with the
 * full conversation and this turn's tool results in hand — see {@code AiAccountantServiceImpl}.
 */
public final class AiRouteSelection {

  private final AiRoute route;
  private final String used;

  private AiRouteSelection(AiRoute route, String used) {
    this.route = route;
    this.used = used;
  }

  static AiRouteSelection routed(AiRoute route, String used) {
    return new AiRouteSelection(route == null ? AiRoute.unknown() : route, used);
  }

  /** The route to run the turn on. Never null. */
  public AiRoute route() {
    return route;
  }

  /**
   * {@code router}, {@code router_continuation}, {@code adapter}, {@code adapter_fallback} or
   * {@code router_continuation_rejected} — see {@link AiRouterLog}.
   */
  public String used() {
    return used;
  }
}
