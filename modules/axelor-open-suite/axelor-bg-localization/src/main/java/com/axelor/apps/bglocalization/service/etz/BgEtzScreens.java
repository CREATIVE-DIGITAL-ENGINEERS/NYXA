package com.axelor.apps.bglocalization.service.etz;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * КЪДЕ СЕ ПОПЪЛВА ВСЕКИ РЕКВИЗИТ — екран › панел › „поле“, така както ги вижда човек.
 *
 * <p><b>Защо отделно от {@link BgEtzGap}.</b> Пречката казва КАКВО липсва и защо, на езика на
 * Приложение № 1 („т. 22 (Код по ЕКАТТЕ)“). Човекът пред трудовия договор обаче търси ПОЛЕ: без
 * пътя до него отказът е верен и безполезен. Пътят е свойство на нашите екрани, не на регистъра —
 * затова живее тук, в една таблица, а не в двайсет низа из резолвера.
 *
 * <p><b>⚠️ ЗАГЛАВИЯТА СА ТЕЗИ ОТ ЕКРАНА.</b> „Код по НКИД“ е етикетът на дружеството, въпреки че т.
 * 21 се казва „Код по КИД“; „Дата на подписване“ е етикетът на договора за т. 10. Преименувани тук
 * „за яснота“, те биха пратили човека да търси поле, което няма.
 */
public final class BgEtzScreens {

  private BgEtzScreens() {}

  private static final String CONTRACT = "Трудов договор";
  private static final String RELATIONSHIP = CONTRACT + " › Правоотношение";
  private static final String PAY = CONTRACT + " › Възнаграждение и работно време";
  private static final String EMPLOYEE = "Служител";

  private static final Map<BgEtz62Field, String> SCREENS = new EnumMap<>(BgEtz62Field.class);

  static {
    SCREENS.put(
        BgEtz62Field.EIK,
        "Дружество › партньорът на дружеството › „ЕИК/Булстат“ (Регистрационен номер)");
    // т. 1 се извежда от вида договор — човек поправя ТАМ, не в несъществуващо поле „вид
    // правоотношение“.
    SCREENS.put(BgEtz62Field.EMPLOYTYPE, RELATIONSHIP + " › „Вид договор“");
    SCREENS.put(BgEtz62Field.EGN, EMPLOYEE + " › Идентификация › „ЕГН/ЛНЧ“");
    SCREENS.put(BgEtz62Field.EGNTYPE, EMPLOYEE + " › Идентификация › „Тип идентификатор“");
    SCREENS.put(BgEtz62Field.FIRSTNAME, EMPLOYEE + " › Контакт › „Първо име“");
    SCREENS.put(BgEtz62Field.SECONDNAME, EMPLOYEE + " › Контакт › „Презиме“");
    SCREENS.put(BgEtz62Field.THIRDNAME, EMPLOYEE + " › Контакт › „Фамилия“");
    SCREENS.put(BgEtz62Field.CONTRACTDATE, CONTRACT + " › „Дата на подписване“");
    SCREENS.put(BgEtz62Field.CONTRACTTYPE, RELATIONSHIP + " › „Вид договор“");
    SCREENS.put(BgEtz62Field.BEGINDATE, CONTRACT + " › „Начална дата“");
    SCREENS.put(BgEtz62Field.TERMDATE, CONTRACT + " › „Крайна дата“");
    SCREENS.put(BgEtz62Field.SALARY, PAY + " › „Основно месечно възнаграждение“");
    SCREENS.put(BgEtz62Field.CODENKPD, RELATIONSHIP + " › „Код по НКПД“");
    SCREENS.put(BgEtz62Field.CODEKID, "Дружество › Българска локализация › „Код по НКИД“");
    SCREENS.put(BgEtz62Field.CODEEKATTE, RELATIONSHIP + " › „Код по ЕКАТТЕ (място на работа)“");
    SCREENS.put(BgEtz62Field.CODEHOURS, PAY + " › „Договорени часове на ден“");
    SCREENS.put(BgEtz62Field.WORKINGHOURS, PAY + " › „Договорени часове на ден“");
    SCREENS.put(
        BgEtz62Field.VACATIONDAYS, CONTRACT + " › Стаж и отпуск › „Полагаем основен отпуск (дни)“");
    // датата е на записа за прекратяване (пренася се в договора); преди записа — на
    // договора.
    SCREENS.put(
        BgEtz62Field.ENDDATE,
        CONTRACT
            + " › Прекратяване (BG) › „Дата на действително прекратяване“ (след записа за"
            + " прекратяване — само през него)");
    // Основанието е в РЕЧНИКА, сочен от договора: членът и актът са две полета там.
    SCREENS.put(
        BgEtz62Field.ENDREASON,
        CONTRACT
            + " › „Основание за прекратяване“ → записът в речника › „Основание (член)“ и"
            + " „Нормативен акт“");
  }

  /**
   * Пътят до полето, или {@code null}, когато реквизитът няма поле на екран (напр. т. 25, т. 30).
   */
  public static String of(BgEtz62Field field) {
    return field == null ? null : SCREENS.get(field);
  }

  /** Полето по етикета на пречката („т. 22 (Код по ЕКАТТЕ)“), или {@code null}. */
  static BgEtz62Field fieldOf(String where) {
    if (where == null) {
      return null;
    }
    for (BgEtz62Field field : BgEtz62Field.values()) {
      if (field.label().equals(where)) {
        return field;
      }
    }
    return null;
  }

  /**
   * Отказът ЗА ЧОВЕК: всяко поле ВЕДНЪЖ, с причините и екрана, където се поправя.
   *
   * <p>⚠️ Едно поле може да носи две пречки — резолверът казва „договорът няма код по НКПД“, а
   * условната задължителност — „т. 19 е задължителна при вид 01 и тип 1“. За човека това е ЕДНО
   * празно поле; два реда биха изглеждали като две различни липси.
   */
  public static String describe(List<BgEtzGap> gaps) {
    Map<String, List<String>> byWhere = new LinkedHashMap<>();
    List<String> general = new ArrayList<>();
    if (gaps != null) {
      for (BgEtzGap gap : gaps) {
        String detail =
            gap.getDetail() == null || gap.getDetail().isEmpty()
                ? gap.getKind().getText()
                : gap.getDetail();
        String where = gap.getWhere();
        if (where == null) {
          // BgEtzMandatory пише етикета В ТЕКСТА („т. 22 (Код по ЕКАТТЕ) е задължително при …“):
          // същото поле, а не обща пречка.
          BgEtz62Field field = fieldStarting(detail);
          if (field == null) {
            general.add(detail);
            continue;
          }
          where = field.label();
          detail = detail.substring(where.length()).trim();
        }
        List<String> details = byWhere.computeIfAbsent(where, k -> new ArrayList<>());
        if (!details.contains(detail)) {
          details.add(detail);
        }
      }
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<String>> entry : byWhere.entrySet()) {
      line(sb, entry.getKey() + " — " + String.join("; ", entry.getValue()));
      String screen = of(fieldOf(entry.getKey()));
      if (screen != null) {
        sb.append(" → попълнете: ").append(screen);
      }
    }
    for (String detail : general) {
      line(sb, detail);
    }
    return sb.toString();
  }

  /** Полето, чийто етикет започва текста — най-дългият, за да не хване „т. 23“ вместо „т. 23А“. */
  private static BgEtz62Field fieldStarting(String text) {
    BgEtz62Field best = null;
    for (BgEtz62Field field : BgEtz62Field.values()) {
      String label = field.label();
      if (text.startsWith(label) && (best == null || label.length() > best.label().length())) {
        best = field;
      }
    }
    return best;
  }

  private static void line(StringBuilder sb, String text) {
    if (sb.length() > 0) {
      sb.append('\n');
    }
    sb.append("• ").append(text);
  }
}
