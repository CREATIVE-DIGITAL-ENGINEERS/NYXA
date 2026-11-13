package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.stock.db.StockMove;

/**
 * Обработчик след реализиране на складово движение — точката, в която ИЗП закача осчетоводяването
 * на изписването.
 *
 * <p>Закача се през Guice Multibinder: {@code Multibinder.newSetBinder(binder(),
 * BgStockMoveRealizedHandler.class).addBinding().to(...)}. Множеството е обявено празно в {@code
 * BgLocalizationModule}, тъй че е налично и без нито един обработчик.
 *
 * <p>{@link BgStockMoveGuard} вика всички обработчици след УСПЕШНО реализиране, в транзакцията на
 * реализирането. Грешката на обработчик се лови и се записва в лога — реализирането не се връща
 * назад (решение 4 на ИЗП: неосчетоводимото изписване се отбелязва, не спира движението).
 *
 * <p>⚠️ Хванатото изключение не отменя маркировката „само за отмяна“ на транзакцията. Обработчик,
 * чиято грешка идва от JPA (flush, ограничение), ще върне назад и реализирането. Затова
 * обработчикът проверява ПРЕДИ да пише, а провала си записва като данни (ред с ERROR), не като
 * изключение.
 */
public interface BgStockMoveRealizedHandler {

  /** Движението вече е реализирано: статусът е 3, realDate е попълнена. */
  void afterRealize(StockMove move) throws Exception;
}
