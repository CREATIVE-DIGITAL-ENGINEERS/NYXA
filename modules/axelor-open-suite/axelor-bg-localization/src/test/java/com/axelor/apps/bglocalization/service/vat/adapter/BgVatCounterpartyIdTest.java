package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ИН на контрагента в дневниците (02-07 / 03-08) и ДДС номерът за VIES — таблицата на ППЗДДС Прил.
 * № 12 и § 1 т. 1 ДР ППЗДДС. ЕГН никога.
 */
class BgVatCounterpartyIdTest {

  private static final Integer LEGAL = 1;
  private static final Integer PERSON = 2;
  private static final String NO_ID = BgVatCounterpartyId.NO_ID;

  /** Валиден 9-цифрен ЕИК. */
  private static final String EIK = "123456786";

  /** Валиден ЕГН. */
  private static final String EGN = "7501010010";

  /** Валиден ЕГН, роден на 15.01.2005 (месец 41). */
  private static final String EGN_2005 = "0541151230";

  /** Служебен номер на НАП: 307…, нито ЕИК, нито ЕГН (месец 00). */
  private static final String SERVICE = "3070000001";

  private static final Object[][] TABLE = {
    // случай, вид, признак, регистрационен код, ДДС номер, държава → дневник, VIES
    {"ДДС номер в България", LEGAL, null, EIK, "BG" + EIK, "BG", "BG" + EIK, "BG" + EIK},
    {"ДДС номер без префикс → BG", LEGAL, null, null, EIK, "BG", "BG" + EIK, "BG" + EIK},
    {"ДДС номер в ДЧ", LEGAL, null, null, "DE123456789", "DE", "DE123456789", "DE123456789"},
    {"Гърция с EL", LEGAL, null, null, "EL123456789", "GR", "EL123456789", "EL123456789"},
    {"Северна Ирландия XI", LEGAL, null, null, "XI123456789", "GB", "XI123456789", "XI123456789"},
    {"ДДС номер бие ЕИК", LEGAL, null, EIK, "DE123456789", "DE", "DE123456789", "DE123456789"},
    {"Швейцария — не е ДЧ", LEGAL, null, null, "CHE-123.456.789", "CH", NO_ID, null},
    {"Великобритания — не е ДЧ", LEGAL, null, null, "GB123456789", "GB", NO_ID, null},
    {"ЕИК (б. „а“)", LEGAL, null, EIK, null, "BG", EIK, null},
    {"ЕИК без адрес", LEGAL, null, EIK, null, null, EIK, null},
    {"„BG“ пред ЕИК в регистрационния код", LEGAL, null, "BG" + EIK, null, "BG", EIK, null},
    {"БУЛСТАТ (б. „б“) на юридическо лице", LEGAL, "2", EIK, null, "BG", EIK, null},
    {"БУЛСТАТ на физическо лице", PERSON, "2", EIK, null, "BG", EIK, null},
    {"БУЛСТАТ на физическо лице без признак", PERSON, null, EIK, null, "BG", EIK, null},
    {"БУЛСТАТ на чуждестранно лице", LEGAL, "2", EIK, null, "US", EIK, null},
    {"служебен номер (б. „в“/„г“), признак 1", PERSON, "1", SERVICE, null, "BG", SERVICE, null},
    {"служебен номер на чуждестранно лице", LEGAL, null, SERVICE, null, "US", SERVICE, null},
    {"ЕГН — признак 0", PERSON, "0", EGN, null, "BG", NO_ID, null},
    {"ЕГН — физическо лице без признак", PERSON, null, EGN, null, "BG", NO_ID, null},
    {"ЕГН от 2000-те", PERSON, null, EGN_2005, null, "BG", NO_ID, null},
    {"служебен номер без признак", PERSON, null, SERVICE, null, "BG", SERVICE, null},
    {"307… с признак „ЕГН“ — не е служебен", PERSON, "0", SERVICE, null, "BG", NO_ID, null},
    {"ЕГН като стар 10-цифрен БУЛСТАТ", PERSON, "2", EGN, null, "BG", NO_ID, null},
    {"ЕГН на юридическо лице (ЕТ) — липсват данни", LEGAL, null, EGN, null, "BG", null, null},
    {"ЛНЧ — признак 1", PERSON, "1", "1001234567", null, "BG", NO_ID, null},
    {"физическо лице без ИН по § 1", PERSON, null, null, null, "BG", NO_ID, null},
    {"физическо лице с невалиден код", PERSON, null, "12345", null, "BG", NO_ID, null},
    {"чуждестранно лице без регистрация", LEGAL, null, null, null, "US", NO_ID, null},
    {"чуждестранно лице с чужд регистров номер", LEGAL, null, "HRB 12345", null, "DE", NO_ID, null},
    {"признак 3 — без идентификатор", PERSON, "3", null, null, "DE", NO_ID, null},
    {"признак 3 на юридическо лице", LEGAL, "3", null, null, null, NO_ID, null},
    {"българско юридическо лице без данни", LEGAL, null, null, null, "BG", null, null},
    {"без вид, адрес и данни", null, null, null, null, null, null, null},
  };

  @Test
  void theTable() {
    List<String> failures = new ArrayList<>();
    for (Object[] row : TABLE) {
      BgVatCounterpartyId id =
          BgVatCounterpartyId.of(
              (Integer) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5]);
      String got = id.getDiaryId() + " / " + id.getVatNumber();
      String want = row[6] + " / " + row[7];
      if (!want.equals(got)) {
        failures.add(row[0] + ": очаквано " + want + ", получено " + got);
      }
    }
    assertEquals(new ArrayList<String>(), failures);
  }

  /** ЕГН не излиза в дневника при нито една комбинация на вид, признак и държава. */
  @Test
  void anEgnIsNeverTheDiaryId() {
    for (Integer type : new Integer[] {null, LEGAL, PERSON}) {
      for (String flag : new String[] {null, "0", "1", "2", "3"}) {
        for (String country : new String[] {null, "BG", "DE", "US"}) {
          for (String egn : new String[] {EGN, EGN_2005, "BG" + EGN}) {
            String diary = BgVatCounterpartyId.of(type, flag, egn, null, country).getDiaryId();
            assertTrue(
                diary == null || !diary.contains(egn.replace("BG", "")),
                type + "/" + flag + "/" + country + "/" + egn + " → " + diary);
          }
        }
      }
    }
  }

  /**
   * Служебният номер се познава по „307…“ и не може да е ЕГН: третата цифра на ЕГН е първата на
   * месеца (0–5; 1800-те 21–32, 2000-те 41–52).
   */
  @Test
  void noValidEgnStartsWith307() {
    assertTrue(BgIdentifierValidator.isValidEgn(EGN));
    assertTrue(BgIdentifierValidator.isValidEgn(EGN_2005));
    for (int rest = 0; rest < 10_000_000; rest += 997) {
      String code = String.format("307%07d", rest);
      assertFalse(BgIdentifierValidator.isValidEgn(code), code);
    }
  }

  /**
   * „BG“ + ЕГН като ДДС номер: от 01.01.2024 номерът на физическо лице е „BG“ + служебният номер по
   * чл. 94 ал. 1 ЗДДС (§ 1 т. 1 б. „в“ ДР ППЗДДС). Признак за проверка, не отказ.
   */
  @Test
  void aVatNumberThatIsBgPlusAnEgn_isFlagged() {
    assertTrue(BgVatCounterpartyId.looksLikeEgn("BG" + EGN));
    assertTrue(BgVatCounterpartyId.looksLikeEgn("bg " + EGN));
    assertTrue(BgVatCounterpartyId.looksLikeEgn("BG" + EGN_2005));
    // Без префикс номерът се чете като български — и пак се хваща.
    String vat = BgVatCounterpartyId.of(PERSON, null, null, EGN, "BG").getVatNumber();
    assertEquals("BG" + EGN, vat);
    assertTrue(BgVatCounterpartyId.looksLikeEgn(vat));

    for (String other :
        new String[] {
          null,
          "",
          "BG" + EIK, // 9 цифри — ЕИК
          "BG" + SERVICE, // служебен номер, не минава ЕГН
          "BG7501010011", // грешна контролна цифра
          "BG7513010016", // месец 13, контролната цифра е вярна
          "DE" + EGN, // друга държава
          "BG" + EGN + "1" // 11 цифри
        }) {
      assertFalse(BgVatCounterpartyId.looksLikeEgn(other), other);
    }
  }

  /** „999…“ е за дневника; във VIES не влиза — там е само ДДС номер. */
  @Test
  void theNoIdCode_isNeverAViesNumber() {
    BgVatCounterpartyId id = BgVatCounterpartyId.of(PERSON, null, EGN, null, "BG");
    assertEquals(NO_ID, id.getDiaryId());
    assertEquals(null, id.getVatNumber());
  }
}
