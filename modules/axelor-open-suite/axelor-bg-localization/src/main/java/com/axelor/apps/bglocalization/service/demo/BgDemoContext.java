package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Company;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * СЪСТОЯНИЕТО НА ЕДНО НАЛИВАНЕ — дружеството, наборът, годината и кодовете от CSV-тата.
 *
 * <p>Записите се пазят по id, не като обекти: стъпките вървят в отделни транзакции (както ръчната
 * работа на екрана), а обект от предишна транзакция е откачен. {@link #find} ги чете наново.
 */
public final class BgDemoContext {

  private final Long companyId;
  private final BgDemoDataset dataset;
  private final BgDemoDates dates;

  /** „вид:код от CSV“ → id, напр. {@code partner:C01}, {@code product:P03}, {@code bank:EUR}. */
  private final Map<String, Long> ids = new HashMap<>();

  private final List<String> log = new ArrayList<>();

  /**
   * Фактура → складовото ѝ движение (връщането по кредитно известие взема себестойността оттам).
   */
  private final Map<Long, Long> stockMoves = new HashMap<>();

  public BgDemoContext(Long companyId, BgDemoDataset dataset, int year) {
    this.companyId = companyId;
    this.dataset = dataset;
    this.dates = new BgDemoDates(year);
  }

  public Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  public Long companyId() {
    return companyId;
  }

  public BgDemoDataset dataset() {
    return dataset;
  }

  public BgDemoDates dates() {
    return dates;
  }

  public void put(String kind, String code, Model record) {
    ids.put(kind + ":" + code, record.getId());
  }

  public boolean has(String kind, String code) {
    return ids.containsKey(kind + ":" + code);
  }

  /** Записът с този код от набора; липсващ код е грешка в данните. */
  public <T extends Model> T find(Class<T> type, String kind, String code) {
    Long id = ids.get(kind + ":" + code);
    if (id == null) {
      throw new IllegalArgumentException("Демо наборът няма " + kind + " с код „" + code + "“");
    }
    return JPA.em().find(type, id);
  }

  public List<BgDemoCsv.Row> rows(String file) {
    return BgDemoCsv.read(dataset, file);
  }

  public void rememberStockMove(Long invoiceId, Long stockMoveId) {
    stockMoves.put(invoiceId, stockMoveId);
  }

  public Long stockMoveOf(Long invoiceId) {
    return stockMoves.get(invoiceId);
  }

  private boolean withoutPayroll;

  /** Наливане без ТРЗ (само за проверката на „само ТРЗ“ върху налято счетоводство). */
  void withoutPayroll(boolean value) {
    this.withoutPayroll = value;
  }

  boolean withoutPayroll() {
    return withoutPayroll;
  }

  public void log(String line) {
    log.add(line);
  }

  public List<String> log() {
    return log;
  }
}
