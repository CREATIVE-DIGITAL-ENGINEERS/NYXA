package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.db.BgArt73Para1Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ КАПАНЪТ НА КОЛОНА 14: генерираният getter връща 0, а образецът иска ПРАЗНО.
 *
 * <p>{@code BgArt73Para1Record.xml} нарочно НЕ дава {@code default="0"} на {@code personCount},
 * точно за да може „не се попълва“ да се различава от „нула лица“. Axelor обаче генерира {@code
 * return personCount == null ? 0 : personCount} за всяко {@code Integer} — и разликата умира в
 * getter-а, не в базата.
 *
 * <p>Без превода тук колона 14 щеше да носи „0“ на ВСЕКИ ред, тоест попълнена колона там, където
 * указание за попълване т. 5 я дава само на кодовете за брой лица. Нула лица е твърдение, което
 * нито един подаден ред не прави.
 */
class BgArt73Para1PersonCountTrapTest {

  @Test
  @DisplayName("Непопълнената колона 14 остава ПРАЗНА, въпреки че getter-ът връща 0")
  void непопълнената_колона_остава_празна() {
    BgArt73Para1Record record = new BgArt73Para1Record();
    // ⚠️ Нищо не е присвоено — точно както го оставя агрегаторът за код без брой лица.
    assertEquals(Integer.valueOf(0), record.getPersonCount(), "котва: getter-ът връща 0, не null");
    assertNull(BgArt73Para1FileCollector.personCount(record));
  }

  @Test
  @DisplayName("Попълнената колона 14 се пренася както е")
  void попълнената_колона_се_пренася() {
    BgArt73Para1Record record = new BgArt73Para1Record();
    record.setPersonCount(7);
    assertEquals(Integer.valueOf(7), BgArt73Para1FileCollector.personCount(record));
  }
}
