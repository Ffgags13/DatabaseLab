package index;

import core.ColumnDef;
import core.TableSchema;
import java.util.Objects;

public record IndexDefinition(String column, IndexKind kind, boolean unique) {

    public IndexDefinition {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(kind,   "kind");
        if (column.isBlank())
            throw new IllegalArgumentException("Имя колонки индекса пустое");
    }

    public static IndexDefinition of(String column, IndexKind kind, boolean unique) {
        return new IndexDefinition(column, kind, unique);
    }

    public static IndexDefinition hash(String column, boolean unique) {
        return new IndexDefinition(column, IndexKind.HASH, unique);
    }

    public static IndexDefinition tree(String column, boolean unique) {
        return new IndexDefinition(column, IndexKind.TREE, unique);
    }

    /** PK всегда индексируется уникальным хешем. */
    public static IndexDefinition forPrimaryKey(TableSchema schema) {
        ColumnDef pk = schema.pkColumn();
        return new IndexDefinition(pk.name(), IndexKind.HASH, true);
    }

    /** Имя файла индекса рядом с {@code .dat}: {@code <table>.idx.<column>}. */
    public String fileName(String tableName) {
        return tableName + ".idx." + column;
    }
}