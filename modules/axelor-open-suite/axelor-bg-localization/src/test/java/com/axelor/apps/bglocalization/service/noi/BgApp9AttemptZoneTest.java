package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „ПОСЛЕДЕН ОПИТ“ Е В МЕСТНО ВРЕМЕ — часовата зона на дружеството, иначе Europe/Sofia. Часът на
 * сървъра (UTC) би бил три часа назад от часовника на човека.
 */
class BgApp9AttemptZoneTest {

  private static Company company(String timezone) {
    Company c = new Company();
    c.setTimezone(timezone);
    return c;
  }

  @Test
  @DisplayName("Зоната: на дружеството, иначе Europe/Sofia — никога тихо UTC на сървъра")
  void theZoneIsTheCompanyOrSofia() {
    assertEquals(ZoneId.of("Europe/Sofia"), BgApp9ExportService.zoneOf(null));
    assertEquals(ZoneId.of("Europe/Sofia"), BgApp9ExportService.zoneOf(company(null)));
    assertEquals(ZoneId.of("Europe/Sofia"), BgApp9ExportService.zoneOf(company("  ")));
    assertEquals(ZoneId.of("Europe/Sofia"), BgApp9ExportService.zoneOf(company("Не/Зона")));
    assertEquals(ZoneId.of("Europe/London"), BgApp9ExportService.zoneOf(company("Europe/London")));
  }

  @Test
  @DisplayName("Редът започва с часа в Europe/Sofia, без потребител — „система“")
  void theStampIsInLocalTime() {
    DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    ZoneId sofia = ZoneId.of("Europe/Sofia");
    String before = ZonedDateTime.now(sofia).format(f);
    String line = BgApp9ExportService.attempt(company(null), "ОТКАЗ");
    String after = ZonedDateTime.now(sofia).format(f);

    assertTrue(line.startsWith(before) || line.startsWith(after), line);
    assertTrue(line.endsWith(" · система · ОТКАЗ"), line);
  }
}
