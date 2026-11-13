package com.axelor.apps.bglocalization.service.demo;

import java.time.LocalDate;
import java.util.Comparator;

/**
 * ЕДНО ДЕЙСТВИЕ ОТ НАБОРА НА ДАДЕНА ДАТА — фактура, плащане, амортизация, ведомост…
 *
 * <p>Наливането върви по дати, както би вървяла ръчната работа: поредиците на фактурите искат
 * хронология, плащането — осчетоводена фактура, протоколът по чл. 117 — фактурата, амортизацията —
 * края на месеца. В един ден редът е по {@link #rank} (по-малкото първо), после по реда в набора.
 */
public final class BgDemoEvent {

  /** Ред в рамките на деня. */
  public static final int RANK_RATES = 0;

  public static final int RANK_ASSET = 10;
  public static final int RANK_PURCHASE = 20;
  public static final int RANK_SALE = 30;
  public static final int RANK_PAYMENT = 40;
  public static final int RANK_CASH = 50;
  public static final int RANK_STOCK = 60;
  public static final int RANK_MONTH_END = 90;

  /** Действието; хвърля, за да спре наливането с точна причина. */
  public interface Action {
    void run() throws Exception;
  }

  public static final Comparator<BgDemoEvent> ORDER =
      Comparator.comparing((BgDemoEvent e) -> e.date)
          .thenComparingInt(e -> e.rank)
          .thenComparingInt(e -> e.seq);

  private static int counter;

  final LocalDate date;
  final int rank;
  final int seq;
  final String label;
  final Action action;

  public BgDemoEvent(LocalDate date, int rank, String label, Action action) {
    this.date = date;
    this.rank = rank;
    this.label = label;
    this.action = action;
    synchronized (BgDemoEvent.class) {
      this.seq = counter++;
    }
  }

  public LocalDate date() {
    return date;
  }

  public String label() {
    return label;
  }
}
