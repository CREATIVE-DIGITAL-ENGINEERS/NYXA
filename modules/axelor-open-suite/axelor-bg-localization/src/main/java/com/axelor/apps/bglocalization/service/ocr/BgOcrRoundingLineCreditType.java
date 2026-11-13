package com.axelor.apps.bglocalization.service.ocr;

import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.aiocr.service.record.OcrRoundingLineCreditType;
import com.google.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Дава на автоматичния ред за закръгляване СЪЩИЯ вид данъчен кредит като редовете, от които е
 * произлязъл.
 *
 * <h2>Защо изобщо трябва да се попълва</h2>
 *
 * Стотинката е част от същата данъчна основа и трябва да влезе в същата колона на дневника за
 * покупки. {@code BgVatDocumentCollector.accumulatePurchaseColumns} маршрутизира ВСЕКИ ред на
 * фактурата по неговия {@code bgVatCreditType}; празна стойност там не се пропуска, а се приема за
 * ПЪЛЕН данъчен кредит (к.10/к.11) и добавя предупреждение „Ред без вид данъчен кредит". Тоест
 * оставен празен, редът пак влиза в дневника — само че мълчаливо и с предупреждение.
 *
 * <h2>Какво прави</h2>
 *
 * <ul>
 *   <li>Всички редове на кофата с ЕДНА и съща непразна стойност → копира я.
 *   <li>Всички празни → „1" (пълен кредит): точно третирането, което колекторът така или иначе би
 *       приложил на тези редове, но записано явно, за да няма разминаване и предупреждение.
 *   <li>Различни непразни стойности → ОТКАЗ. Щом самите редове не са единодушни, за корекцията няма
 *       един верен отговор, а сгрешена колона в дневника е сгрешена декларация.
 * </ul>
 */
@Singleton
public class BgOcrRoundingLineCreditType implements OcrRoundingLineCreditType {

  /** bg.vat.credit.type.select — „1" пълен данъчен кредит (к.10/к.11). */
  private static final String CREDIT_FULL = "1";

  @Override
  public String applyFromBucket(InvoiceLine roundingLine, List<InvoiceLine> bucketLines) {
    if (roundingLine == null) {
      return null;
    }
    Set<String> stated = new LinkedHashSet<>();
    if (bucketLines != null) {
      for (InvoiceLine line : bucketLines) {
        String creditType = line.getBgVatCreditType();
        if (creditType != null && !creditType.trim().isEmpty()) {
          stated.add(creditType.trim());
        }
      }
    }
    if (stated.size() > 1) {
      return "Редовете на фактурата имат различен вид данъчен кредит ("
          + String.join(", ", stated)
          + "), тъй че за реда за закръгляване няма еднозначен. Фактурата остава за преглед —"
          + " изравнете данъчния кредит на редовете и валидирайте отново.";
    }
    roundingLine.setBgVatCreditType(stated.isEmpty() ? CREDIT_FULL : stated.iterator().next());
    return null;
  }
}
