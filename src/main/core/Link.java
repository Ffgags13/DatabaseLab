package main.core;

/**
 * Описание связи внешнего ключа (Foreign Key) с родительской таблицей
 * и правил поддержания ссылочной целостности при изменении или удалении записей.
 */
public class Link {
    public enum Behaviour {
        CASCADE,
        SET_NULL,
        RESTRICT
    }

    private String parentTableName; // Имя таблицы, на которую ссылаемся
    private Behaviour onUpdate;
    private Behaviour onDelete;

    // Пустой конструктор для Gson
    private Link() {
    }

    /**
     * Создаёт описание связи с родительской таблицей.
     *
     * @param parentTableName имя родительской таблицы, на первичный ключ которой ссылается столбец
     * @param onUpdate        правило поведения при обновлении PK родителя (если {@code null}, применяется {@link Behaviour#RESTRICT})
     * @param onDelete        правило поведения при удалении записи родителя (если {@code null}, применяется {@link Behaviour#RESTRICT})
     */
    public Link(String parentTableName, Behaviour onUpdate, Behaviour onDelete) {
        this.parentTableName = parentTableName;
        this.onUpdate = (onUpdate != null) ? onUpdate : Behaviour.RESTRICT;
        this.onDelete = (onDelete != null) ? onDelete : Behaviour.RESTRICT;
    }

    public String getParentTableName() {
        return parentTableName;
    }

    public Behaviour getOnUpdate() {
        return onUpdate;
    }

    public Behaviour getOnDelete() {
        return onDelete;
    }
}