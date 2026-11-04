package core;

/**
 * Определение колонки. Неизменяемый объект-значение.
 * <p>
 * Инварианты проверяются в компактном конструкторе.
 */
public record ColumnDef(String name, ColumnType type, boolean nullable) {

    public ColumnDef {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Имя колонки не может быть пустым");
        if (type == null)
            throw new IllegalArgumentException("Колонка «" + name + "»: тип не задан");
    }
}