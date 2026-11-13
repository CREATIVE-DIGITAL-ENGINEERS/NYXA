package com.axelor.apps.bglocalization.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.bankpayment.db.BankOrder;
import com.axelor.apps.bankpayment.db.BankOrderFileFormat;
import com.axelor.apps.bankpayment.db.BankOrderLine;
import com.axelor.apps.bankpayment.service.bankorder.file.transfer.BankOrderFile00100103Service;
import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ДОКАЗАТЕЛСТВОТО, ЧЕ ФАЙЛЪТ Е ВАЛИДЕН pain.001.001.03 — срещу САМАТА схема, не срещу нашето мнение
 * за нея.
 *
 * <p><b>⚠️ ПУСКА СЕ ИСТИНСКИЯТ UPSTREAM ГЕНЕРАТОР, А НЕ КОПИЕ НА ФОРМАТА.</b> {@link
 * BankOrderFile00100103Service} се вика както в производството; единственото, което тестът подменя,
 * е ПАПКАТА, в която пише — {@code getFolderPath()} е {@code protected} точно за това. Тест, който
 * сглобява XML сам, би доказал, че тестът е верен, не че файлът е.
 *
 * <p><b>⚠️ И ЗАТОВА ТЕСТЪТ НЕ ИСКА НИТО GUICE, НИТО БАЗА.</b> Пътят от {@code BankOrder} до
 * записания файл минава през чисти getter-и, {@code ObjectFactory} и {@code
 * MarshallingHelper#marshallFile} (обикновен JAXB {@code Marshaller}) — нито едно {@code
 * Beans.get}. Ентитетите се сглобяват в паметта, без {@code persist}.
 *
 * <p><b>⚠️ ПОСЛЕДНИЯТ ТЕСТ ТУК Е ЗАДЪЛЖИТЕЛЕН, А НЕ ИЗЛИШЕН.</b> Валидатор, който винаги връща
 * {@code null}, минава всяка положителна асерция в този файл. Затова има и случай, за който схемата
 * ТРЯБВА да гръмне — иначе „0 грешки“ не значи нищо.
 */
class BgPayrollSepaFileTest {

  private static final String IBAN_COMPANY = "BG80BNBG96611020345678";
  private static final String IBAN_IVAN = "BG18RZBB91550123456789";
  private static final String IBAN_MARIA = "BG72UNCR70001522345678";
  private static final String IBAN_GEORGI = "BG29NSBB96501012345678";

  /**
   * Подменя папката и двоеточията в името: всичко останало е производственият генератор.
   *
   * <p>⚠️ ЗАЩО СЕ ПИПА И ИМЕТО. {@code BankOrderFileService.computeFileName()} форматира часа като
   * {@code HH:mm:ss}. Двоеточието е валиден знак в име на файл под Linux — там върви конвейерът и
   * контейнерът, затова производството не го усеща — но под Windows е забранено и marshal-ът пада с
   * „The filename, directory name, or volume label syntax is incorrect“. Тестът не твърди НИЩО за
   * името (сверява съдържанието срещу XSD-то), затова го обезврежда тук, вместо трите проверки да
   * се пропускат или да светят червено на половината машини.
   *
   * <p>Замяната е безусловна, а не само под Windows: тест, който се държи различно според машината,
   * е собствен капан.
   */
  private static final class Generator extends BankOrderFile00100103Service {

    private final String folder;

    Generator(BankOrder bankOrder, String folder) {
      super(bankOrder);
      this.folder = folder;
    }

    @Override
    protected String getFolderPath() {
      return folder;
    }

    @Override
    public String computeFileName() {
      return super.computeFileName().replace(':', '-');
    }
  }

  private static BankDetails account(String iban, String owner, String bic) {
    BankDetails details = new BankDetails();
    details.setIban(iban);
    details.setOwnerName(owner);
    details.setActive(Boolean.TRUE);
    if (bic != null) {
      Bank bank = new Bank();
      bank.setCode(bic);
      details.setBank(bank);
    }
    return details;
  }

  private static BankOrderLine line(String sequence, BankDetails to, String net, String label) {
    BankOrderLine bankOrderLine = new BankOrderLine();
    bankOrderLine.setSequence(sequence);
    bankOrderLine.setReceiverBankDetails(to);
    bankOrderLine.setBankOrderAmount(new BigDecimal(net));
    bankOrderLine.setReceiverLabel(label);
    return bankOrderLine;
  }

  /** Пробег с три лица, както го строи {@code BgPayrollSepaService}. */
  private static BankOrder payrollRun(List<BankOrderLine> lines) {
    Currency eur = new Currency();
    eur.setCode("EUR");
    eur.setCodeISO(BgPayrollSepaRules.CURRENCY_ISO);

    BankOrderFileFormat format = new BankOrderFileFormat();
    format.setOrderFileFormatSelect(BgPayrollSepaRules.FILE_FORMAT_SELECT);

    PaymentMode paymentMode = new PaymentMode();
    paymentMode.setName("SEPA заплати");

    Company company = new Company();
    company.setName("Нукса ЕООД");
    company.setCurrency(eur);

    BankOrder bankOrder = new BankOrder();
    bankOrder.setPaymentMode(paymentMode);
    bankOrder.setBankOrderFileFormat(format);
    bankOrder.setBankOrderDate(LocalDate.of(2026, 9, 30));
    bankOrder.setSenderBankDetails(account(IBAN_COMPANY, "Нукса ЕООД", "BNBGBGSF"));
    bankOrder.setSenderCompany(company);
    bankOrder.setBankOrderCurrency(eur);
    bankOrder.setFileGenerationDateTime(LocalDateTime.of(2026, 9, 11, 10, 30, 0));
    bankOrder.setBankOrderSeq("BO-2026-09-0001");
    bankOrder.setBankOrderLineList(lines);
    bankOrder.setNbOfLines(lines.size());

    // ⚠️ Както в производството: сборът идва от BankOrderManagementRepository#save през
    // updateTotalAmounts, тоест е ПРОСТ сбор на редовете. Тук се смята по същото правило.
    List<BgPayrollSepaCandidate> asCandidates = new ArrayList<>();
    for (BankOrderLine bankOrderLine : lines) {
      asCandidates.add(
          BgPayrollSepaCandidate.payable(
              1L, "FISH", "x", "IBAN", bankOrderLine.getBankOrderAmount()));
    }
    bankOrder.setArithmeticTotal(BgPayrollSepaRules.arithmeticTotal(asCandidates));
    return bankOrder;
  }

  private static List<BankOrderLine> threePeople(String remittance) {
    List<BankOrderLine> lines = new ArrayList<>();
    lines.add(
        line(
            "BO-2026-09-0001-1",
            account(IBAN_IVAN, "Иван Петров Георгиев", "RZBBBGSF"),
            "1234.56",
            remittance));
    // ⚠️ ВТОРОТО ЛИЦЕ Е БЕЗ BIC НАРОЧНО: fillBic пише NOTPROVIDED и файлът остава валиден.
    lines.add(
        line(
            "BO-2026-09-0001-2",
            account(IBAN_MARIA, "Мария Стоянова Илиева", null),
            "987.65",
            remittance));
    lines.add(
        line(
            "BO-2026-09-0001-3",
            account(IBAN_GEORGI, "Георги Димитров Николов", "NSBBBGSF"),
            "0.01",
            remittance));
    return lines;
  }

  @Test
  @DisplayName("Пробег с три лица дава pain.001.001.03, сверен срещу XSD-то: 0 грешки")
  void threePeopleProduceAValidSepaFile(@TempDir File folder) throws Exception {
    String remittance = BgPayrollSepaRules.remittance(LocalDate.of(2026, 9, 1));
    BankOrder bankOrder = payrollRun(threePeople(remittance));

    File file = new Generator(bankOrder, folder.getAbsolutePath()).generateFile();

    assertNotNull(file, "генераторът трябва да върне файл");
    assertTrue(file.isFile());

    // ═══ СВЕРЯВАНЕТО СРЕЩУ СХЕМАТА ═══
    BgSepaXsdValidator.Result verdict = BgSepaXsdValidator.validate(file);
    assertNull(
        verdict.getNotCheckedReason(),
        "схемата трябва да е намерена — инак всяка асерция тук е празна");
    assertNull(
        verdict.getProblems(), "файлът трябва да е валиден pain.001.001.03 — 0 несъответствия");
    assertTrue(verdict.isClean());

    String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

    // ═══ ЕДИН РЕД НА ЛИЦЕ, ПО НЕГОВИЯ IBAN ═══
    assertEquals(3, count(xml, "<CdtTrfTxInf>"), "по един ред на лице");
    assertTrue(xml.contains("<IBAN>" + IBAN_IVAN + "</IBAN>"));
    assertTrue(xml.contains("<IBAN>" + IBAN_MARIA + "</IBAN>"));
    assertTrue(xml.contains("<IBAN>" + IBAN_GEORGI + "</IBAN>"));
    assertTrue(xml.contains("<Nm>Иван Петров Георгиев</Nm>"));

    // ═══ СУМИТЕ СА НЕТАТА, В EUR ═══
    assertTrue(xml.contains("<InstdAmt Ccy=\"EUR\">1234.56</InstdAmt>"));
    assertTrue(xml.contains("<InstdAmt Ccy=\"EUR\">987.65</InstdAmt>"));
    assertTrue(xml.contains("<InstdAmt Ccy=\"EUR\">0.01</InstdAmt>"));

    // ═══ ТОТАЛЪТ Е СБОРЪТ НА НЕТАТА ═══
    assertTrue(xml.contains("<NbOfTxs>3</NbOfTxs>"));
    assertTrue(xml.contains("<CtrlSum>2222.22</CtrlSum>"), "1234.56 + 987.65 + 0.01");

    // ═══ ОСНОВАНИЕТО ═══
    assertEquals(3, count(xml, "<Ustrd>Работна заплата 09.2026</Ustrd>"));

    // ═══ ПЛАТЕЦЪТ И ДАТАТА НА ИЗПЪЛНЕНИЕ ═══
    assertTrue(xml.contains("<IBAN>" + IBAN_COMPANY + "</IBAN>"));
    assertTrue(xml.contains("<BIC>BNBGBGSF</BIC>"));
    assertTrue(xml.contains("<ReqdExctnDt>2026-09-30</ReqdExctnDt>"));

    // ═══ ЛИПСВАЩИЯТ BIC НЕ Е ПРОБЛЕМ ═══
    assertTrue(
        xml.contains("<Id>NOTPROVIDED</Id>"),
        "лице без BIC минава с NOTPROVIDED, а не поваля файла");
  }

  @Test
  @DisplayName("Пробег с ЕДНО лице също е валиден — един ред не е особен случай")
  void oneLineIsAlsoValid(@TempDir File folder) throws Exception {
    List<BankOrderLine> lines = new ArrayList<>();
    lines.add(
        line(
            "BO-2026-09-0002-1",
            account(IBAN_IVAN, "Иван Петров Георгиев", "RZBBBGSF"),
            "1500.00",
            BgPayrollSepaRules.remittance(LocalDate.of(2026, 9, 1))));

    File file = new Generator(payrollRun(lines), folder.getAbsolutePath()).generateFile();

    assertTrue(BgSepaXsdValidator.validate(file).isClean());
    String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertEquals(1, count(xml, "<CdtTrfTxInf>"));
    assertTrue(xml.contains("<CtrlSum>1500.00</CtrlSum>"));
  }

  /**
   * ПАЗАЧЪТ НА САМИЯ ПАЗАЧ.
   *
   * <p>⚠️ Името на получателя е {@code Max140Text}. JAXB маршалира 200 знака без да гъкне — виж
   * {@link BgSepaXsdValidator}. Ако този тест мине с „0 грешки“, значи сверяването не сверява нищо
   * и всяка друга асерция тук е празна.
   */
  @Test
  @DisplayName("Име над 140 знака ТРЯБВА да падне на XSD-то — инак валидаторът е декор")
  void tooLongNameIsCaughtByTheSchema(@TempDir File folder) throws Exception {
    StringBuilder tooLong = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      tooLong.append("Иван Петров ");
    }
    List<BankOrderLine> lines = new ArrayList<>();
    lines.add(
        line(
            "BO-2026-09-0003-1",
            account(IBAN_IVAN, tooLong.toString(), "RZBBBGSF"),
            "100.00",
            "Работна заплата 09.2026"));

    File file = new Generator(payrollRun(lines), folder.getAbsolutePath()).generateFile();

    BgSepaXsdValidator.Result verdict = BgSepaXsdValidator.validate(file);

    assertNull(
        verdict.getNotCheckedReason(), "схемата трябва да е построена, инак тестът не мери нищо");
    assertNotNull(verdict.getProblems(), "схемата трябва да отхвърли име над Max140Text");
    assertTrue(verdict.getProblems().contains("Max140Text"));
    assertTrue(verdict.getProblems().contains("pain.001.001.03"));
  }

  private static int count(String haystack, String needle) {
    int total = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      total++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return total;
  }
}
