package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРЕПРАТКАТА КЪМ ФИШ СЕ ПОКАЗВА С НОМЕРА МУ, НЕ С ИДЕНТИФИКАТОРА.
 *
 * <p>„Коригира фиш“ на коригиращото копие показва номера на фиша, не идентификатора му. Без колона
 * за име Axelor показва препратката с идентификатора — във всяка форма и решетка, която сочи фиш.
 * Мери се ИМЕТО, което рамката взима, тоест същото, по което формата пише стойността на полето.
 */
class BgPaySlipNameColumnTest {

  @Test
  @DisplayName("Колоната за име на фиша е номерът му")
  void aPaySlipIsNamedByItsReference() {
    Property name = Mapper.of(BgPaySlip.class).getNameField();
    assertNotNull(name, "⚠️ фишът няма колона за име — препратките показват идентификатора");
    assertEquals("reference", name.getName());
  }
}
