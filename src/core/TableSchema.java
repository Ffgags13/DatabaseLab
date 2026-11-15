package core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Описание таблицы: упорядоченный список колонок + первичный ключ.
 * <p>
 * Порядок колонок важен: по нему {@link RecordCodec} пишет поля в payload.
 * Неизменяемый объект-значение; инварианты проверяются в конструкторе.
 */
public final class TableSchema {

    private final List<ColumnDef> columns;
    private final ColumnDef pkColumn;
    private final Map<String, ColumnDef> byName;

    /**
     * @param columns хотя бы одна колонка, имена уникальны, ровно один {@code pk}
     * @throws IllegalArgumentException если инварианты нарушены
     */
    public TableSchema(List<ColumnDef> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Схема должна содержать хотя бы одну колонку");
        }
        this.columns = List.copyOf(columns);

        this.byName = this.columns.stream()
                .collect(Collectors.toMap(ColumnDef::name, c -> c, (a, b) -> {
                    throw new IllegalArgumentException(
                            "Дублирующееся имя колонки: " + a.name());
                }));

        List<ColumnDef> pks = this.columns.stream()
                .filter(ColumnDef::pk)
                .toList();
        if (pks.size() != 1) {
            throw new IllegalArgumentException(
                    "В схеме должен быть ровно один PK, найдено: " + pks.size());
        }
        this.pkColumn = pks.get(0);
    }

    /** Упорядоченный (неизменяемый) список колонок. */
    public List<ColumnDef> getColumns() {
        return columns;
    }

    /** Колонка-первичный ключ. Никогда не {@code null}. */
    public ColumnDef pkColumn() {
        return pkColumn;
    }

    /** Найти колонку по имени; пусто, если нет. */
    public Optional<ColumnDef> findColumn(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}