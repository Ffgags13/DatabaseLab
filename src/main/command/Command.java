package main.command;

import main.core.ColumnDef;
import main.core.Condition;
import main.index.IndexKind;

import java.util.List;
import java.util.Map;

public sealed interface Command {

    record CreateTable(String name, List<ColumnDef> columns) implements Command {}

    record DropTable(String name) implements Command {}

    record CreateIndex(String table, String column, IndexKind kind, boolean unique)
            implements Command {}

    record DropIndex(String table, String column) implements Command {}

    record Insert(String table, Map<String, Object> row) implements Command {}

    record Update(String table, Map<String, Object> patch, Condition where) implements Command {}

    record Delete(String table, Condition where) implements Command {}

    record Select(String table, List<String> columns, Condition where) implements Command {}
}