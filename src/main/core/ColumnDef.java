package main.core;

public record ColumnDef(String name, ColumnType type, boolean nullable, boolean isUniquie) {
    //Значение null считаются равными, поэтому если столбец Uniquie
    public ColumnDef(String name, ColumnType type, boolean nullable){
      this(name, type, nullable, false);
    }
}
