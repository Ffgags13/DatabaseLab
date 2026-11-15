package main.core;

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