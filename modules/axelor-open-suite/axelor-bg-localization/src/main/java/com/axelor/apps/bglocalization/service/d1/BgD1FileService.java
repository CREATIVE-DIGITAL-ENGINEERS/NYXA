package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD6FileAdapter;
import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.google.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * РЕДОВЕТЕ → БАЙТОВЕ: CP1251, CRLF и маркер за край на файл.
 *
 * <p><b>⚠️ ПРЕИЗПОЛЗВА {@link BgVatFileWriter}, НЕ ГО ПИПА.</b> Двете тежки изисквания към всеки
 * файл към НАП — Windows-1251 и CRLF — вече живеят там, сверени и покрити със свидетели, и се
 * ползват от ДДС-стека. Втори енкодер за същото е втори шанс да се появи BOM или платформено ново
 * редче.
 *
 * <p><b>⚠️ ЕДНО НЕЩО, КОЕТО ДДС-ПИСАЧЪТ НЕ ПРАВИ, И НЕ БИВА ДА НАУЧИ: CTRL+Z.</b> Заповедта по чл.
 * 8 ал. 5 иска Д1 да завършва с {@code CHR(26)}. ДДС файловете НЕ го искат — добавен там, той би
 * счупил работещ формат. Затова маркерът се долепя ТУК, върху вече кодираните байтове: това е
 * свойство на ТОЗИ формат, не на писача.
 *
 * <p>⚠️ И се долепя СЛЕД последното CRLF, а не вместо него: „всеки запис завършва с CR+LF“ важи и
 * за последния. Писачът вече слага CRLF след всеки ред — тоест тук се добавя само един байт.
 */
@Singleton
public class BgD1FileService {

  /** Маркерът за край на файл — {@code CTRL+Z}, {@code CHR(26)}, {@code 0x1A}. */
  public static final byte END_OF_FILE = 0x1A;

  private final BgVatFileWriter writer;
  private final BgD1FileAdapter d1Adapter;
  private final BgD6FileAdapter d6Adapter;

  @Inject
  public BgD1FileService(
      BgVatFileWriter writer, BgD1FileAdapter d1Adapter, BgD6FileAdapter d6Adapter) {
    this.writer = writer;
    this.d1Adapter = d1Adapter;
    this.d6Adapter = d6Adapter;
  }

  /**
   * Байтовете на {@code EMPL2021.TXT} за подадените записи.
   *
   * @param sourceFlag поле 53 — ЕИК на разработчика или на осигурителната каса
   */
  public byte[] d1Bytes(List<BgD1Record> records, String sourceFlag) throws IOException {
    List<String> lines = new ArrayList<>();
    if (records != null) {
      for (BgD1Record record : records) {
        if (record != null) {
          lines.add(d1Adapter.toLine(record, sourceFlag));
        }
      }
    }
    return withEndOfFile(writer.toBytes(lines, BgVatFileWriter.ENCODING_CP1251));
  }

  /**
   * Байтовете на файла за Декларация обр. 6 — ЕДИН ред, защото декларацията е една.
   *
   * @param sourceFlag поле 59 — същият ЕИК като поле 53 на Д1
   */
  public byte[] d6Bytes(BgD6Declaration declaration, String sourceFlag) throws IOException {
    return d6Bytes(declaration, BgD6CompensationLedger.none(), sourceFlag);
  }

  /** Същото, с обезщетенията по вид 27 на месеца — те дават свои блокове ({@link BgD6Blocks}). */
  public byte[] d6Bytes(
      BgD6Declaration declaration, BgD6CompensationLedger ledger, String sourceFlag)
      throws IOException {
    List<String> lines = new ArrayList<>();
    if (declaration != null) {
      lines.add(d6Adapter.toLine(declaration, sourceFlag, ledger));
    }
    return withEndOfFile(writer.toBytes(lines, BgVatFileWriter.ENCODING_CP1251));
  }

  private static byte[] withEndOfFile(byte[] body) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 1);
    out.write(body);
    out.write(END_OF_FILE);
    return out.toByteArray();
  }
}
