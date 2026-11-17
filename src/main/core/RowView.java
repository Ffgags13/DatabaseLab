package main.core;

import main.core.domainExceptions.IncorrectTypeInConditionException;

/**
 * Легковесное представление строки таблицы для фильтрации записей по условиям.
 * <p>
 * Используется в предикатах ({@code Predicate<RowView>}) при выборке, обновлении
 * и удалении данных, инкапсулируя сравнение значений с учётом типов столбцов.
 */
public class RowView {
    private final Object[] values;
    private final ColumnType[] types;
    private final String[] colNames;

    /**
     * Создаёт представление строки на основе параллельных массивов значений, типов и имён столбцов.
     *
     * @param values   значения полей текущей строки
     * @param types    типы соответствующих столбцов
     * @param colNames имена столбцов (используются при формировании исключений)
     */
    public RowView(Object[] values, ColumnType[] types, String[] colNames) {
        this.values = values;
        this.types = types;
        this.colNames = colNames;
    }

    /**
     * Проверяет значение поля по индексу на равенство с целевым значением ({@code value == target}).
     *
     * @param index  индекс столбца в массиве {@code values}
     * @param target значение для сравнения
     * @return {@code true}, если значения равны
     * @throws IncorrectTypeInConditionException если тип {@code target} несовместим с типом столбца
     */
    public boolean eq(int index, Object target) {
        try {
            return types[index].eq(values[index], target);
        } catch (RuntimeException e) {
            throw new IncorrectTypeInConditionException(colNames[index]);
        }
    }

    /**
     * Проверяет, что значение поля по индексу строго больше целевого ({@code value > target}).
     *
     * @param index  индекс столбца в массиве {@code values}
     * @param target значение для сравнения
     * @return {@code true}, если значение поля больше {@code target}
     * @throws IncorrectTypeInConditionException если тип {@code target} несовместим с типом столбца
     */
    public boolean gt(int index, Object target) {
        try {
            return types[index].gt(values[index], target);
        } catch (RuntimeException e) {
            throw new IncorrectTypeInConditionException(colNames[index]);
        }
    }

    /**
     * Проверяет, что значение поля по индексу строго меньше целевого ({@code value < target}).
     *
     * @param index  индекс столбца в массиве {@code values}
     * @param target значение для сравнения
     * @return {@code true}, если значение поля меньше {@code target}
     * @throws IncorrectTypeInConditionException если тип {@code target} несовместим с типом столбца
     */
    public boolean lt(int index, Object target) {
        try {
            return types[index].lt(values[index], target);
        } catch (RuntimeException e) {
            throw new IncorrectTypeInConditionException(colNames[index]);
        }
    }

    /**
     * Проверяет, является ли значение поля по указанному индексу {@code null}.
     *
     * @param index индекс столбца в массиве {@code values}
     * @return {@code true}, если значение равно {@code null}
     */
    public boolean isNull(int index) {
        return values[index] == null;
    }
}