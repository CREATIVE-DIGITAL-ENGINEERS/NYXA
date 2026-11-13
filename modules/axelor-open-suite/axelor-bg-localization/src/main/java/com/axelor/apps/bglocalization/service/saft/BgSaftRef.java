package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ЕДИН ЗАПИС ЗА ЕДНА НАХОДКА — етикетът за човека и записът, който го поправя.
 *
 * <p><b>⚠️ ПО ЕДНА НАХОДКА НА ЗАПИС, НЕ СПИСЪК В ТЕКСТА</b> (G6a, решение от 14.10.2026).
 * Assembler-ите събират препратки вместо низове; {@link #error}/{@link #warning} правят от всяка
 * препратка отделна находка със същия код и указател към записа, тоест екранът (G6b) само ги
 * пренася в {@code SaftValidationIssue}.
 *
 * <p>Препратка без запис ({@code model == null}) — за неща, които не са запис в Axelor: код по НАП,
 * данък, известен само по кода си, поле на файла. Находката тогава е пак една на нещо, но без линк.
 *
 * <p>Редът е по етикета, после по модела и id-то — за да са находките стабилни между пусканията.
 */
public final class BgSaftRef implements Comparable<BgSaftRef> {

  public static final String MOVE = "com.axelor.apps.account.db.Move";
  public static final String MOVE_LINE = "com.axelor.apps.account.db.MoveLine";
  public static final String ACCOUNT = "com.axelor.apps.account.db.Account";
  public static final String INVOICE = "com.axelor.apps.account.db.Invoice";
  public static final String PAYMENT_MODE = "com.axelor.apps.account.db.PaymentMode";
  public static final String PARTNER = "com.axelor.apps.base.db.Partner";
  public static final String PRODUCT = "com.axelor.apps.base.db.Product";
  public static final String UNIT = "com.axelor.apps.base.db.Unit";
  public static final String COMPANY = "com.axelor.apps.base.db.Company";
  public static final String PERIOD = "com.axelor.apps.base.db.Period";

  private static final Comparator<BgSaftRef> ORDER =
      Comparator.comparing((BgSaftRef r) -> r.label)
          .thenComparing(r -> r.model, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(r -> r.id, Comparator.nullsFirst(Comparator.naturalOrder()));

  private final String label;
  private final String model;
  private final Long id;

  private BgSaftRef(String label, String model, Long id) {
    this.label = Objects.requireNonNull(label, "label");
    this.model = model;
    this.id = id;
  }

  /** Запис в Axelor. */
  public static BgSaftRef of(String label, String model, Long id) {
    return new BgSaftRef(label, model, id);
  }

  /** Нещо без запис — само етикет. */
  public static BgSaftRef text(String label) {
    return new BgSaftRef(label, null, null);
  }

  public static BgSaftRef move(Long id, String label) {
    return new BgSaftRef(label, MOVE, id);
  }

  public static BgSaftRef partner(Long id, String label) {
    return new BgSaftRef(label, PARTNER, id);
  }

  public static BgSaftRef account(Long id, String label) {
    return new BgSaftRef(label, ACCOUNT, id);
  }

  public String getLabel() {
    return label;
  }

  public String getModel() {
    return model;
  }

  public Long getId() {
    return id;
  }

  /** Същият запис с друг етикет — напр. с броя на редовете. */
  public BgSaftRef withLabel(String newLabel) {
    return new BgSaftRef(newLabel, model, id);
  }

  @Override
  public int compareTo(BgSaftRef other) {
    return ORDER.compare(this, other);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BgSaftRef)) {
      return false;
    }
    BgSaftRef other = (BgSaftRef) o;
    return label.equals(other.label)
        && Objects.equals(model, other.model)
        && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, model, id);
  }

  @Override
  public String toString() {
    return label;
  }

  // ─────────────── находките ───────────────

  /** По една ГРЕШКА на препратка: {@code text} + етикетът, с указател към записа. */
  public static void error(
      List<BgDeclarationFileFinding> findings,
      String code,
      String text,
      Collection<BgSaftRef> items) {
    for (BgSaftRef item : items) {
      findings.add(item.attach(BgDeclarationFileFinding.error(code, text + item.label)));
    }
  }

  /** По едно ПРЕДУПРЕЖДЕНИЕ на препратка. */
  public static void warning(
      List<BgDeclarationFileFinding> findings,
      String code,
      String text,
      Collection<BgSaftRef> items) {
    for (BgSaftRef item : items) {
      findings.add(item.attach(BgDeclarationFileFinding.warning(code, text + item.label)));
    }
  }

  /** По едно ПРЕДУПРЕЖДЕНИЕ на препратка, с броя ѝ: „етикет (N {@code unit})“. */
  public static void counted(
      List<BgDeclarationFileFinding> findings,
      String code,
      String text,
      Map<BgSaftRef, Integer> items,
      String unit) {
    items.forEach(
        (item, count) ->
            findings.add(
                item.attach(
                    BgDeclarationFileFinding.warning(
                        code, text + item.label + " (" + count + " " + unit + ")"))));
  }

  /** По една ГРЕШКА на препратка, с броя ѝ. */
  public static void countedError(
      List<BgDeclarationFileFinding> findings,
      String code,
      String text,
      Map<BgSaftRef, Integer> items,
      String unit) {
    items.forEach(
        (item, count) ->
            findings.add(
                item.attach(
                    BgDeclarationFileFinding.error(
                        code, text + item.label + " (" + count + " " + unit + ")"))));
  }

  /** Находката с указател към този запис; без запис — непроменена. */
  public BgDeclarationFileFinding attach(BgDeclarationFileFinding finding) {
    return model == null ? finding : finding.at(model, id);
  }
}
