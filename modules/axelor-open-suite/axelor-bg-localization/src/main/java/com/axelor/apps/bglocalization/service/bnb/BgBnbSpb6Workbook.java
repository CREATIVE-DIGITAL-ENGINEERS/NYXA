package com.axelor.apps.bglocalization.service.bnb;

import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Balance;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Form;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Period;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Result;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Row;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Sheet;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * EXCEL-ЪТ НА СПБ-6А/6Б — ВЪВ ВИДА НА ОБРАЗЕЦА, НЕ ШАБЛОНЪТ ЗА ИСИС.
 *
 * <p>Шаблонът за зареждане в Портала на ИСИС се тегли само от самия портал (Указания, Приложение 6,
 * т. 5.5.2) и е заключен — тук той не се възпроизвежда. Файлът е работен: листовете „СПБ-6А“ и
 * „СПБ-6Б“ повтарят образеца (колони 1–10) с колона 11 извън него — кои партньори са в реда.
 *
 * <p>Всяка клетка със стойност по подразбиране е жълта и носи бележка „провери“; броят им е горе.
 * Под прага от 100 000 евро справката пак се прави, но горе пише, че не се подава.
 */
public final class BgBnbSpb6Workbook {

  private BgBnbSpb6Workbook() {}

  static final String CHECK = "провери: ";

  static final String[] HEADERS = {
    "Тип на чуждестранното лице",
    "Сектор на чуждестранното лице",
    "Тип на %s",
    "Държава на чуждестранното лице",
    "Валута на %s",
    "Матуритет на %s",
    "Размер в началото на тримесечието в хил. валутни единици",
    "Промени в(ъв) %s, несвързани с плащане /код/",
    "Промени в(ъв) %s, несвързани с плащане /+/- в хил. валутни единици/",
    "Размер в края на тримесечието в хил. валутни единици",
    "Контрагенти (извън образеца)"
  };

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final String[] ROMAN = {"", "I", "II", "III", "IV"};

  /** Редът с броя на жълтите клетки — тестовете го търсят по този текст. */
  static final String DEFAULTS_LABEL = "Стойности по подразбиране за проверка (жълти клетки): ";

  /** Първият ред с данни (0-базиран) — след заглавието, статуса и двата реда на шапката. */
  static final int FIRST_DATA_ROW = 9;

  public static byte[] write(Result result, String companyName, String eik) throws IOException {
    // POI 3.9: XSSFWorkbook още не е Closeable — няма try-with-resources.
    XSSFWorkbook wb = new XSSFWorkbook();
    Styles s = new Styles(wb);
    for (Form form : Form.values()) {
      form(wb, s, result, result.sheet(form), companyName, eik);
    }
    checks(wb, s, result);
    details(wb, s, result);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    wb.write(out);
    return out.toByteArray();
  }

  private static final class Styles {
    final CellStyle bold;
    final CellStyle header;
    final CellStyle check;
    final CellStyle red;
    final CellStyle green;
    final CellStyle wrap;

    Styles(XSSFWorkbook wb) {
      Font b = wb.createFont();
      b.setBoldweight(Font.BOLDWEIGHT_BOLD);
      bold = wb.createCellStyle();
      bold.setFont(b);

      header = wb.createCellStyle();
      header.setFont(b);
      header.setWrapText(true);
      header.setBorderBottom(CellStyle.BORDER_THIN);
      header.setBorderTop(CellStyle.BORDER_THIN);
      header.setBorderLeft(CellStyle.BORDER_THIN);
      header.setBorderRight(CellStyle.BORDER_THIN);

      check = wb.createCellStyle();
      check.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
      check.setFillPattern(CellStyle.SOLID_FOREGROUND);

      Font r = wb.createFont();
      r.setBoldweight(Font.BOLDWEIGHT_BOLD);
      r.setColor(IndexedColors.RED.getIndex());
      red = wb.createCellStyle();
      red.setFont(r);

      Font g = wb.createFont();
      g.setBoldweight(Font.BOLDWEIGHT_BOLD);
      g.setColor(IndexedColors.GREEN.getIndex());
      green = wb.createCellStyle();
      green.setFont(g);

      wrap = wb.createCellStyle();
      wrap.setWrapText(true);
    }
  }

  private static void form(
      XSSFWorkbook wb, Styles s, Result result, Sheet data, String companyName, String eik) {
    Form form = data.form;
    Period period = result.period;
    org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet(form.code);
    Drawing drawing = sh.createDrawingPatriarch();
    CreationHelper help = wb.getCreationHelper();

    text(sh, 0, 0, "Форма " + form.code, s.bold);
    text(sh, 0, 9, "Образец по " + form.ordinance, null);
    text(
        sh,
        1,
        0,
        "Тримесечен отчет за "
            + form.plural
            + (form == Form.A
                ? " на местни лица от чуждестранни лица"
                : " на местни лица към чуждестранни лица"),
        s.bold);
    text(
        sh,
        2,
        0,
        period.isFiling()
            ? "за " + ROMAN[period.quarter] + " тримесечие на " + period.year + " година"
            : "Работен лист за "
                + period.year
                + " г. (в началото — "
                + DATE.format(period.opening)
                + ", в края — "
                + DATE.format(period.closing)
                + "). БНБ не приема годишна "
                + form.code
                + " — подава се тримесечно.",
        period.isFiling() ? null : s.red);
    text(sh, 3, 0, "Тип на отчета: първоначален (P)", null);
    status(sh, s, 4, period, data);
    text(sh, 5, 0, DEFAULTS_LABEL + data.defaults(), data.defaults() > 0 ? s.red : s.green);
    text(
        sh,
        6,
        0,
        "1. МЕСТНО ЛИЦЕ   1.1. Наименование: "
            + nz(companyName)
            + "   1.2. ЕИК/Код по БУЛСТАТ: "
            + nz(eik)
            + "   (т. 1.3–1.10 — само при първоначална регистрация или промяна)",
        null);

    org.apache.poi.ss.usermodel.Row head = sh.createRow(7);
    org.apache.poi.ss.usermodel.Row nums = sh.createRow(8);
    String noun = form == Form.A ? "вземането" : "задължението";
    for (int c = 0; c < HEADERS.length; c++) {
      Cell h = head.createCell(c);
      h.setCellValue(String.format(HEADERS[c], c == 2 ? form.plural : noun));
      h.setCellStyle(s.header);
      Cell n = nums.createCell(c);
      n.setCellValue(c < 10 ? String.valueOf(c + 1) : "11");
      n.setCellStyle(s.header);
      sh.setColumnWidth(c, c == 10 ? 60 * 256 : 16 * 256);
    }
    head.setHeightInPoints(75);

    int r = FIRST_DATA_ROW;
    for (Row row : data.rows) {
      org.apache.poi.ss.usermodel.Row x = sh.createRow(r);
      checked(
          x,
          0,
          row.type,
          row.likelyRelated
              ? "вероятно свързано лице – провери D1–D3 (има салдо по 405/415)"
              : "тип на чуждестранното лице D1–D4; по подразбиране D4 — несвързано лице",
          s,
          drawing,
          help);
      checked(
          x,
          1,
          row.sector,
          "сектор на чуждестранното лице; по подразбиране S11 за фирма и S14 за физическо лице",
          s,
          drawing,
          help);
      x.createCell(2).setCellValue(row.kind);
      x.createCell(3).setCellValue(row.country);
      x.createCell(4).setCellValue(row.currency);
      checked(
          x,
          5,
          row.maturity,
          "първоначален договорен матуритет; по подразбиране S — до една година включително",
          s,
          drawing,
          help);
      x.createCell(6).setCellValue(row.openingK());
      x.createCell(9).setCellValue(row.closingK());
      Cell who = x.createCell(10);
      who.setCellValue(String.join("; ", row.partners));
      who.setCellStyle(s.wrap);
      r++;
    }
    if (data.rows.isEmpty()) {
      text(
          sh,
          r++,
          0,
          "Няма " + form.plural + " към чуждестранни лица над 500 валутни единици.",
          null);
    }

    r++;
    text(sh, r, 0, "Описание на другите " + form.plural + " (с код F89):", s.bold);
    if (data.hasOther()) {
      org.apache.poi.ss.usermodel.Row x = sh.getRow(r);
      checked(
          x,
          3,
          "",
          "опиши характера на „другите“ — задължително при F89 (напр. покупко-продажба на дялове,"
              + " аванс за недвижим имот)",
          s,
          drawing,
          help);
    }
    r += 2;
    r = legend(sh, s, r, form);
    r++;
    text(
        sh,
        r++,
        0,
        "Известно ми е, че за посочването на неверни данни нося отговорност по чл. 313 от Наказателния кодекс.",
        null);
    text(sh, r++, 0, "СЪСТАВИЛ ОТЧЕТА", s.bold);
    text(sh, r++, 0, "Име и фамилия:                Длъжност:                Подпис:", null);
    text(sh, r++, 0, "Телефон:                Електронна поща:                Дата:", null);
  }

  private static void status(
      org.apache.poi.ss.usermodel.Sheet sh, Styles s, int r, Period period, Sheet data) {
    String amounts = "в началото: " + eur(data.openingEur) + ", в края: " + eur(data.closingEur);
    Status status = data.status();
    String line;
    CellStyle style;
    if (status == Status.FILE) {
      style = s.red;
      line =
          (period.isFiling()
                  ? "ПОДАВА СЕ до " + DATE.format(period.deadline())
                  : "Над прага в началото или в края на годината")
              + " — общата сума на "
              + data.form.plural
              + " е равна на или над 100 000 € ("
              + amounts
              + "). Канал: Портал на ИСИС с КУКЕП"
              + (data.rows.size() > BgBnbSpb6.PAPER_MAX_RECORDS
                  ? "; записите са повече от 5 — само по електронен път (Наредба № 27, чл. 14, ал. 5)."
                  : " или на хартия в БНБ.");
    } else if (status == Status.BELOW) {
      style = s.green;
      line =
          "НЕ СЕ ПОДАВА — общата сума на "
              + data.form.plural
              + " е под прага от 100 000 € и в началото, и в края ("
              + amounts
              + "). Указания към Наредба № 27 (в сила от 01.04.2026), "
              + data.form.guideline
              + ".";
    } else {
      style = s.red;
      line =
          "ПРАГЪТ НЕ Е ПРОВЕРЕН — липсва курс на ЕЦБ за "
              + String.join(", ", data.missingRates)
              + " ("
              + amounts
              + " без тях).";
    }
    text(sh, r, 0, line, style);
  }

  private static int legend(org.apache.poi.ss.usermodel.Sheet sh, Styles s, int r, Form form) {
    String noun = form == Form.A ? "вземането" : "задължението";
    String[][] tables = {
      {"Табл.1. Тип на чуждестранното лице"},
      {"D1", "Преки и непреки чуждестранни инвеститори"},
      {"D2", "Преки и непреки инвестиционни предприятия в чужбина"},
      {"D3", "Други свързани чуждестранни предприятия"},
      {"D4", "Несвързани чуждестранни лица /различни от D1, D2 и D3/"},
      {"Табл.2. Сектор на чуждестранното лице"},
      {"S11", "Нефинансови предприятия"},
      {"S121", "Централна банка"},
      {"S122", "Депозитни институции, без централната банка"},
      {"S123", "Фондове на паричния пазар"},
      {"S124", "Инвестиционни фондове, различни от тези на паричния пазар"},
      {
        "S125",
        "Други финансови посредници без застрахователните (осигурителните) дружества и пенсионните фондове"
      },
      {"S126", "Финансови спомагателни организации"},
      {"S127", "Каптивни финансови институции и заемодатели"},
      {"S128", "Застрахователни (осигурителни) дружества"},
      {"S129", "Пенсионни фондове"},
      {"S13", "Държавно управление"},
      {"S14", "Домакинства"},
      {"S15", "Нетърговски организации, обслужващи домакинствата"},
      {"Табл.3. Тип на " + form.plural},
      {
        "F81",
        (form == Form.A ? "Вземания" : "Задължения")
            + " по търговски кредити и аванси и начислените по тях лихви (по внос, износ и"
            + " вътрешнообщностна търговия със стоки и услуги)"
      },
      form == Form.B ? new String[] {"F51D", "Задължения по начислени дивиденти"} : null,
      {
        "F89",
        form == Form.A
            ? "Други вземания (без вземания по финансови кредити)"
            : "Други задължения (без задължения по финансови кредити и облигационни заеми)"
      },
      {"Табл.4. Матуритет на " + noun},
      {"L", "Дългосрочни — със срок над една година (вкл. безсрочни)"},
      {"S", "Краткосрочни — със срок една или под една година"},
      {"Табл.5. Промени в(ъв) " + noun + ", несвързани с плащане"},
      {"15", "Опрощаване (двустранно)"},
      {"18", "Отписване (едностранно)"},
      {"27", "Промяна в типа и/или сектора на чуждестранното лице"},
      {"28", "Промяна в държавата на контрагента"},
      {"29", "Промяна в матуритета от S към L при просрочие (едностранно)"},
    };
    for (String[] t : tables) {
      if (t == null) {
        continue;
      }
      if (t.length == 1) {
        r++;
        text(sh, r++, 0, t[0], s.bold);
      } else {
        org.apache.poi.ss.usermodel.Row x = sh.createRow(r++);
        x.createCell(0).setCellValue(t[0]);
        x.createCell(1).setCellValue(t[1]);
      }
    }
    return r;
  }

  private static void checks(XSSFWorkbook wb, Styles s, Result result) {
    org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("Проверки");
    sh.setColumnWidth(0, 160 * 256);
    int r = 0;
    text(sh, r++, 0, "Какво да се провери, преди справката да се пренесе в ИСИС", s.bold);
    r++;
    if (result.warnings.isEmpty()) {
      text(sh, r++, 0, "Няма салда, които да не са влезли във формите.", s.green);
    }
    for (String w : result.warnings) {
      text(sh, r++, 0, w, s.wrap);
    }
    r++;
    text(sh, r++, 0, "Как е сметнато", s.bold);
    List<String> notes =
        List.of(
            "Източник: Указания към Наредба № 27 на БНБ, в сила от 01.04.2026 г. (§ 4 ПЗР), за отчетни"
                + " периоди от 01.01.2026 г. (§ 3 ПЗР): чл. 8, т. 1 и т. 2; образци по чл. 9, ал. 1, т. 3"
                + " и т. 4 от Наредба № 27 (членът от Наредбата — по цитата в указанията, да се"
                + " потвърди в самата Наредба).",
            "Чуждестранно лице = държавата в основния адрес на партньора не е BG. Клон на"
                + " чуждестранно лице, регистриран в България, е местно лице — да се провери ръчно.",
            "Салдото е по сметка, партньор и валута, разделено по знак: дебитното е вземане"
                + " (СПБ-6А), кредитното — задължение (СПБ-6Б). Началото и краят се делят поотделно.",
            "Сметки → вид (тълкуване, указанията не назовават сметки — да се потвърди): F81 —"
                + " 401, 402, 404, 405, 408, 411, 412, 414, 415, 416, 418; F89 — 498, 499.",
            "F51D (начислени дивиденти, само СПБ-6Б): указанията не посочват сметка — не се попълва"
                + " автоматично, да се потвърди и при нужда да се добави ръчно.",
            "Колони 8 и 9 (промени без плащане: 15, 18, 27, 28, 29) не се попълват автоматично.",
            "Сумите са в оригиналната валута, в хиляди, закръглени (1 500 → 2). Левовете са"
                + " превалутирани в евро по 1,95583. Ред, който е 0 и в началото, и в края, не се посочва.",
            "Прагът от 100 000 € е по точните суми, превалутирани по последния курс на ЕЦБ в"
                + " системата към датата на салдото (Указания, чл. 5, ал. 11).",
            "Колоната „Контрагенти“ не е част от образеца. Редовете на различни контрагенти с еднакви"
                + " колони 1–6 са обединени, както позволяват указанията; лицата с 405/415 са на"
                + " отделен ред.",
            "Електронното подаване е през xls шаблона от Портала на ИСИС (теглени от портала,"
                + " подписани с КУКЕП, detached .p7s) — този файл не е шаблонът.");
    for (String n : notes) {
      text(sh, r++, 0, n, s.wrap);
    }
  }

  private static void details(XSSFWorkbook wb, Styles s, Result result) {
    org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("Разбивка");
    String[] head = {
      "Партньор", "Държава", "Сметка", "Валута", "Салдо в началото (Дт +)", "Салдо в края (Дт +)"
    };
    org.apache.poi.ss.usermodel.Row h = sh.createRow(0);
    for (int c = 0; c < head.length; c++) {
      Cell cell = h.createCell(c);
      cell.setCellValue(head[c]);
      cell.setCellStyle(s.header);
      sh.setColumnWidth(c, (c == 0 ? 50 : 18) * 256);
    }
    int r = 1;
    for (Balance b : result.details) {
      org.apache.poi.ss.usermodel.Row x = sh.createRow(r++);
      x.createCell(0).setCellValue(b.partnerName);
      x.createCell(1).setCellValue(b.country);
      x.createCell(2).setCellValue(b.account);
      x.createCell(3).setCellValue(b.currency);
      x.createCell(4).setCellValue(b.opening.doubleValue());
      x.createCell(5).setCellValue(b.closing.doubleValue());
    }
  }

  private static void checked(
      org.apache.poi.ss.usermodel.Row x,
      int c,
      String value,
      String note,
      Styles s,
      Drawing drawing,
      CreationHelper help) {
    Cell cell = x.createCell(c);
    cell.setCellValue(value);
    cell.setCellStyle(s.check);
    ClientAnchor anchor = help.createClientAnchor();
    anchor.setCol1(c);
    anchor.setCol2(c + 4);
    anchor.setRow1(x.getRowNum());
    anchor.setRow2(x.getRowNum() + 4);
    Comment comment = drawing.createCellComment(anchor);
    comment.setString(help.createRichTextString(CHECK + note));
    comment.setAuthor("Nyxa");
    cell.setCellComment(comment);
  }

  private static void text(
      org.apache.poi.ss.usermodel.Sheet sh, int r, int c, String value, CellStyle style) {
    org.apache.poi.ss.usermodel.Row row = sh.getRow(r) == null ? sh.createRow(r) : sh.getRow(r);
    Cell cell = row.createCell(c);
    cell.setCellValue(value);
    if (style != null) {
      cell.setCellStyle(style);
    }
  }

  private static String eur(BigDecimal value) {
    return value == null
        ? "няма курс"
        : value.setScale(0, RoundingMode.HALF_UP).toPlainString() + " €";
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
