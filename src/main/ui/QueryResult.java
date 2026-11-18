package main.ui;

import main.core.TableSchema;

import java.util.List;
import java.util.Map;

/**
 * Единая модель ответа на выполнение запроса для отображения в UI.
 * <p>
 * Инкапсулирует либо текстовое уведомление/ошибку, либо табличные данные,
 * полученные после выборки строк целиком или по отдельным столбцам.
 *
 * @param success - флаг успешного выполнения запроса
 * @param message - текстовое сообщение или описание ошибки
 * @param schema - схема таблицы (используется при выводе всех строк через {@code rowsData})
 * @param rowsData - список строк таблицы в порядке столбцов схемы
 * @param columnsData - словарь значений в формате {@code имя_столбца -> массив_значений}
 */
public record QueryResult(
        boolean success,
        String message,
        TableSchema schema,
        List<Object[]> rowsData,
        Map<String, Object[]> columnsData
) {
    /**
     * Создаёт успешный результат с текстовым уведомлением.
     *
     * @param msg - текст сообщения для пользователя
     * @return экземпляр {@link QueryResult} с текстом уведомления
     */
    public static QueryResult message(String msg) {
        return new QueryResult(true, msg, null, null, null);
    }

    /**
     * Создаёт результат с описанием ошибки выполнения запроса.
     *
     * @param errorMsg - текст ошибки
     * @return экземпляр {@link QueryResult} с флагом {@code success = false}
     */
    public static QueryResult error(String errorMsg) {
        return new QueryResult(false, "Ошибка: " + errorMsg, null, null, null);
    }

    /**
     * Создаёт результат с полным набором строк таблицы.
     *
     * @param schema - схема таблицы с описанием столбцов
     * @param rows - список строк таблицы
     * @return экземпляр {@link QueryResult} с табличными строками
     */
    public static QueryResult tableRows(TableSchema schema, List<Object[]> rows) {
        return new QueryResult(true, null, schema, rows, null);
    }

    /**
     * Создаёт результат с выборкой по конкретным столбцам.
     *
     * @param columns - словарь значений по столбцам
     * @return экземпляр {@link QueryResult} с данными выборки
     */
    public static QueryResult tableColumns(Map<String, Object[]> columns) {
        return new QueryResult(true, null, null, null, columns);
    }
}
