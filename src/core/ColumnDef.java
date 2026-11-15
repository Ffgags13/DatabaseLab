package core;

public record ColumnDef(String name, ColumnType type, boolean nullable,
                        boolean pk, boolean unique) {

    public ColumnDef {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Имя колонки не может быть пустым");
        if (type == null)
            throw new IllegalArgumentException("Колонка «" + name + "»: тип не задан");
        if (pk && nullable)
            throw new IllegalArgumentException("Колонка «" + name + "»: PK не может быть nullable");
        if (pk && !unique)
            throw new IllegalArgumentException("Колонка «" + name + "»: PK обязан быть unique");
    }

    public static ColumnDef plain(String name, ColumnType type) {
        return new ColumnDef(name, type, true, false, false);
    }
    public static ColumnDef required(String name, ColumnType type) {
        return new ColumnDef(name, type, false, false, false);
    }
    public static ColumnDef unique(String name, ColumnType type) {
        return new ColumnDef(name, type, false, false, true);
    }
    public static ColumnDef primaryKey(String name, ColumnType type) {
        return new ColumnDef(name, type, false, true, true);
    }
}