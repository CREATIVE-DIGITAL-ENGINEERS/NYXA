package com.axelor.apps.bglocalization.service.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.app.BgAppConfigLinkService.Outcome;
import com.axelor.apps.bglocalization.service.app.BgAppConfigLinkService.Result;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppBgLocalization;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАПИСЪТ НА НАСТРОЙКИТЕ НА ЗАВАРЕНА БАЗА — свързва се с приложението, ако връзката липсва.
 *
 * <p>„Конфигуриране“ на картата (axelor-studio, {@code AppController#configure}) стига до формата
 * само през {@code App.appBgLocalization}, обратната страна на {@code AppBgLocalization.app}. Запис
 * с празно {@code app} (например създаден на ръка през по-стара форма) остава недостижим и картата
 * казва „No configuration required“. Връзването е ТЯСНО: само при ТОЧНО един запис, празно {@code
 * app} и налично приложение {@code bg-localization}; във всеки друг случай не пипа нищо и казва
 * защо.
 */
class BgAppConfigLinkServiceTest {

  /** Подмените вместо базата: записите, приложението по код и записа. */
  private static final class Harness extends BgAppConfigLinkService {
    final List<AppBgLocalization> records = new ArrayList<>();
    App app;
    final List<AppBgLocalization> saved = new ArrayList<>();

    @Override
    protected List<AppBgLocalization> records() {
      return records;
    }

    @Override
    protected App appByCode(String code) {
      return APP_CODE.equals(code) ? app : null;
    }

    @Override
    protected void save(AppBgLocalization record) {
      saved.add(record);
    }
  }

  private static App app(long id) {
    App app = new App();
    app.setId(id);
    app.setCode(BgAppConfigLinkService.APP_CODE);
    return app;
  }

  private static AppBgLocalization record(long id, App app) {
    AppBgLocalization r = new AppBgLocalization();
    r.setId(id);
    r.setApp(app);
    return r;
  }

  @Test
  @DisplayName("един запис с празно app → свързва се с bg-localization и се казва какво е свързано")
  void anUnlinkedSingleRecordIsLinked() {
    Harness s = new Harness();
    s.app = app(26L);
    AppBgLocalization r = record(1L, null);
    s.records.add(r);

    Result result = s.ensureLinked();

    assertEquals(Outcome.LINKED, result.outcome);
    assertSame(s.app, r.getApp(), "записът сочи приложението");
    assertEquals(Arrays.asList(r), s.saved, "и е записан — веднъж");
    assertNotNull(result.message);
    assertTrue(
        result.message.contains("#1") && result.message.contains("#26"),
        "WARNING-ът казва кой запис с кое приложение: " + result.message);

    Result again = s.ensureLinked();
    assertEquals(Outcome.ALREADY_LINKED, again.outcome, "идемпотентно — второто пускане не пипа");
    assertEquals(1, s.saved.size());
  }

  @Test
  @DisplayName("попълнено app → не се пипа нищо")
  void aLinkedRecordIsLeftAlone() {
    Harness s = new Harness();
    s.app = app(26L);
    App other = app(99L);
    AppBgLocalization r = record(1L, other);
    s.records.add(r);

    Result result = s.ensureLinked();

    assertEquals(Outcome.ALREADY_LINKED, result.outcome);
    assertSame(other, r.getApp(), "попълнената връзка не се пренаписва");
    assertTrue(s.saved.isEmpty());
    assertNull(result.message, "нищо за казване");
  }

  @Test
  @DisplayName("два записа → не се пипа нищо, само WARNING")
  void twoRecordsAreLeftAloneWithAWarning() {
    Harness s = new Harness();
    s.app = app(26L);
    AppBgLocalization first = record(1L, null);
    AppBgLocalization second = record(2L, null);
    s.records.add(first);
    s.records.add(second);

    Result result = s.ensureLinked();

    assertEquals(Outcome.MANY_RECORDS, result.outcome);
    assertNull(first.getApp());
    assertNull(second.getApp());
    assertTrue(s.saved.isEmpty(), "при два записа не се избира — човек решава кой е верният");
    assertTrue(result.message != null && result.message.contains("2"), "" + result.message);
  }

  @Test
  @DisplayName("липсващо приложение bg-localization → не се пипа нищо, само WARNING")
  void aMissingAppLeavesTheRecordAloneWithAWarning() {
    Harness s = new Harness();
    AppBgLocalization r = record(1L, null);
    s.records.add(r);

    Result result = s.ensureLinked();

    assertEquals(Outcome.NO_APP, result.outcome);
    assertNull(r.getApp());
    assertTrue(s.saved.isEmpty());
    assertTrue(
        result.message != null && result.message.contains(BgAppConfigLinkService.APP_CODE),
        "" + result.message);
  }

  @Test
  @DisplayName("нула записа → не се пипа нищо, само WARNING (App Install не е минал)")
  void noRecordIsAWarningNotACreation() {
    Harness s = new Harness();
    s.app = app(26L);

    Result result = s.ensureLinked();

    assertEquals(Outcome.NO_RECORD, result.outcome);
    assertTrue(s.saved.isEmpty(), "записът НЕ се създава оттук — това е работа на App Install");
    assertNotNull(result.message);
  }
}
