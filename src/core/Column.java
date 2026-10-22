package core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class Column {

    private final String name;
    private final ColumnType type;
    //private final boolean primaryKey;
    private final boolean nullable;

    private final Object defaultValue;

    public Column(String name, ColumnType type, boolean nullable) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.defaultValue = null;

    }

    public Column(String name, ColumnType type, boolean nullable, Object defaultValue) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.defaultValue = defaultValue;

    }

    //public String name()       { return name; }
    public ColumnType type()   { return type; }
   // public boolean primaryKey(){ return primaryKey; }
    public boolean nullable()  { return nullable; }

    public Object check(Object raw) {
        if (raw == null) {
            if (!nullable)
                throw new IllegalStateException("Колонка '" + name + "' не может быть NULL");
            return null;
        }
        try {
            return switch (type) {
                case INT     -> raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
                case DOUBLE  -> raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().trim());
                case STRING  -> raw.toString();
                case DATE    -> raw instanceof LocalDate d ? d : LocalDate.parse(raw.toString().trim());
                case BOOLEAN -> raw instanceof Boolean b ? b : parseBool(raw.toString().trim());
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalStateException(
                    "Колонка '" + name + "' (" + type + "): " + e.getMessage());
        }
    }

    private static Boolean parseBool(String s) {
        return switch (s.toLowerCase()) {
            case "true", "1"  -> true;
            case "false", "0" -> false;
            default -> throw new IllegalStateException("ожидалось true/false, получено: " + s);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public int compare(Object a, Object b) {
        if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
        return switch (type) {
            case INT     -> Integer.compare((Integer) a, (Integer) b);
            case DOUBLE  -> Double.compare((Double) a, (Double) b);
            case STRING  -> ((String) a).compareTo((String) b);
            case DATE    -> ((LocalDate) a).compareTo((LocalDate) b);
            case BOOLEAN -> Boolean.compare((Boolean) a, (Boolean) b);
        };
    }

    public String format(Object v) {
        return v == null ? "NULL" : v.toString();
    }

    public void write(DataOutput out, Object v) throws IOException {
        out.writeBoolean(v != null);
        if (v == null) return;
        switch (type) {
            case INT     -> out.writeInt((Integer) v);
            case DOUBLE  -> out.writeDouble((Double) v);
            case STRING  -> out.writeUTF((String) v);
            case DATE    -> out.writeLong(((LocalDate) v).toEpochDay());
            case BOOLEAN -> out.writeBoolean((Boolean) v);
        }
    }

    public Object read(DataInput in) throws IOException {
        if (!in.readBoolean()) return null;
        return switch (type) {
            case INT     -> in.readInt();
            case DOUBLE  -> in.readDouble();
            case STRING  -> in.readUTF();
            case DATE    -> LocalDate.ofEpochDay(in.readLong());
            case BOOLEAN -> in.readBoolean();
        };
    }

    @Override public String toString() {
        return name + " " + type
                + ("DEFAULT: " + defaultValue  + " ")
                + (!nullable  ? " NOT NULL"    : "");
    }

    public String getName() {
        return name;
    }
}
