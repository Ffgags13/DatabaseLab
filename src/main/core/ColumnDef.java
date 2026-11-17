package main.core;

/**
 * Описание структуры одного столбца таблицы.
 *
 * @param name      имя столбца
 * @param type      тип хранимых данных
 * @param nullable  допускает ли столбец значения {@code null}
 * @param isUniquie флаг уникальности значений в столбце (при проверке {@code null} не считается дубликатом)
 */
public record ColumnDef(String name, ColumnType type, boolean nullable, boolean isUniquie) {
    //Значение null считаются равными, поэтому если столбец Uniquie
    //Значение null считаются равными

    /**
     * Создаёт описание неуникального столбца ({@code isUniquie = false}).
     *
     * @param name     имя столбца
     * @param type     тип хранимых данных
     * @param nullable допускает ли столбец значения {@code null}
     */
    public ColumnDef(String name, ColumnType type, boolean nullable) {
        this(name, type, nullable, false);
    }
}