package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets.Entry;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОФИТЕ НА Т. 16 — сборът, заб. 1 и трите начина един ден да изчезне.
 *
 * <p>Тестът е ЧИСТ: без база, без фиш, без Guice. Проверява се точно онова, което {@link
 * BgD1DayBuckets} обещава — че дните отиват в СВОЯТА кофа, че сборът по заб. 1 е сборът на шестте,
 * и че непознатата кофа се ВРЪЩА, вместо да мълчи.
 */
class BgD1DayBucketsTest {

  @Test
  @DisplayName("шестте кофи · всеки ден отива в своята, а сборът по заб. 1 е сборът им")
  void everyDayLandsInItsOwnBucketAndTheTotalIsTheirSum() {
    Map<String, Integer> sum =
        BgD1DayBuckets.sum(
            Arrays.asList(
                new Entry(BgD1DayBuckets.B_16_1, 12),
                new Entry(BgD1DayBuckets.B_16_2, 4),
                new Entry(BgD1DayBuckets.B_16_3, 2),
                new Entry(BgD1DayBuckets.B_16_4, 1),
                new Entry(BgD1DayBuckets.B_16_5, 1),
                new Entry(BgD1DayBuckets.B_16_A, 3)));

    assertEquals(12, sum.get(BgD1DayBuckets.B_16_1));
    assertEquals(4, sum.get(BgD1DayBuckets.B_16_2));
    assertEquals(2, sum.get(BgD1DayBuckets.B_16_3));
    assertEquals(1, sum.get(BgD1DayBuckets.B_16_4));
    assertEquals(1, sum.get(BgD1DayBuckets.B_16_5));
    assertEquals(3, sum.get(BgD1DayBuckets.B_16_A));

    assertEquals(
        23,
        BgD1DayBuckets.total(sum),
        "заб. 1 дословно: „В позиции 1 и 2 на т. 16 се вписва СБОРЪТ от дните, отразени в т. 16.1 до"
            + " 16.5 и т. 16.А.“ Числата са РАЗЛИЧНИ нарочно — при равни стойности размяна на две"
            + " кофи не се вижда от сбора.");
  }

  @Test
  @DisplayName("едно и също отсъствие два пъти · дните се СЪБИРАТ, не се презаписват")
  void twoEntriesInOneBucketAdd() {
    Map<String, Integer> sum =
        BgD1DayBuckets.sum(
            Arrays.asList(
                new Entry(BgD1DayBuckets.B_16_3, 5), new Entry(BgD1DayBuckets.B_16_3, 3)));
    assertEquals(
        8,
        sum.get(BgD1DayBuckets.B_16_3),
        "две заявки за отпуск в един месец са два реда. Презаписване вместо събиране би дало 3 —"
            + " по-малкото число, тоест по-малко дни в осигуряване, тоест по-малък стаж.");
  }

  @Test
  @DisplayName("всичките шест ключа СА в картата, включително нулевите")
  void allSixKeysArePresentEvenWhenZero() {
    Map<String, Integer> empty = BgD1DayBuckets.sum(Collections.emptyList());
    assertEquals(
        BgD1DayBuckets.BUCKETS,
        empty.keySet(),
        "т. 16 брои ДНИ, а „нула дни в платен отпуск“ и „няма платен отпуск“ са едно и също"
            + " наблюдение. Пълната карта прави сбора по заб. 1 проверим без шест проверки за null.");
    assertEquals(0, BgD1DayBuckets.total(empty));
  }

  @Test
  @DisplayName("⚠️ т. 16.А е КИРИЛСКО „А“ — латинското „16.A“ е НЕПОЗНАТА кофа и се ОБЯВЯВА")
  void theLatinLetterIsAnUnknownBucketNotASilentDrop() {
    // Латинско „A“ = U+0041. Изписва се еднакво с кирилското и минава всеки визуален преглед.
    Map<String, Integer> sum = BgD1DayBuckets.sum(Collections.singletonList(new Entry("16.A", 7)));

    assertEquals(
        0,
        sum.get(BgD1DayBuckets.B_16_A),
        "седемте дни НЕ влизат в кофата — низовете са различни, колкото и еднакво да изглеждат");
    assertTrue(
        BgD1DayBuckets.unknownBuckets(Collections.singletonList(new Entry("16.A", 7)))
            .contains("16.A"),
        "⚠️ И ТОЧНО ЗАТОВА unknownBuckets СЪЩЕСТВУВА. Без него седем дни ВНР с възнаграждение от"
            + " работодателя щяха да изчезнат от декларацията, а сборът в т. 16 да е с седем"
            + " по-малък — правдоподобно число за месец с отсъствия.");
    assertEquals(
        0x0410,
        BgD1DayBuckets.B_16_A.charAt(3),
        "сравнението е по КОДОВА ТОЧКА, не по изглед: „16.А“ трябва да носи U+0410");
    assertEquals(
        BgD1Point.T16_A_EMPLOYER_SICK,
        BgD1DayBuckets.B_16_A,
        "стойността се ВЗИМА от BgD1Point, а не се преписва — втори литерал е втори шанс за същата"
            + " грешка");
  }

  @Test
  @DisplayName("празна кофа се ПРОПУСКА, не пада — петте вида извън т. 16 са СТОЙНОСТ")
  void anEmptyBucketIsSkippedNotRefused() {
    Map<String, Integer> sum =
        BgD1DayBuckets.sum(
            Arrays.asList(
                new Entry("", 4), new Entry(null, 3), new Entry(BgD1DayBuckets.B_16_1, 2)));
    assertEquals(2, sum.get(BgD1DayBuckets.B_16_1));
    assertEquals(
        2,
        BgD1DayBuckets.total(sum),
        "самоотлъчката и неплатеният над 30 дни не се зачитат за осигурителен стаж, тоест не влизат в"
            + " нито една точка. Отказ тук би спрял декларация заради самоотлъчка.");
    assertTrue(
        BgD1DayBuckets.unknownBuckets(Collections.singletonList(new Entry("", 4))).isEmpty(),
        "празното НЕ е непозната кофа — то е обявено решение, а непознатата е грешка");
  }

  @Test
  @DisplayName("петте вида без кофа са ИЗБРОЕНИ — празна клетка без списък е забравена клетка")
  void theUnbucketedListIsExplicit() {
    assertEquals(
        5,
        BgD1DayBuckets.UNBUCKETED.size(),
        "неплатен над 30 дни (по желание и по производствена необходимост), неприсъствие,"
            + " самоотлъчка и непредставен болничен: "
            + BgD1DayBuckets.UNBUCKETED);
    assertTrue(BgD1DayBuckets.UNBUCKETED.contains("awol"));
    assertTrue(BgD1DayBuckets.UNBUCKETED.contains("unpaid_own_gt30"));
  }
}
