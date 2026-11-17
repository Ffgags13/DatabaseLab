package main.command;

import main.core.*;
import main.core.domainExceptions.AmountValuesException;
import main.core.domainExceptions.IncorrectType;
import main.query.Ast;
import main.query.Parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Выполняет AST-команду над {@link DbCommandApi}.
 * <p>
 * Диспетчеризует {@link Ast.Statement} по методам фасада, приводя литералы
 * к типам столбцов через {@link ColumnType#requireType} и компилируя
 * WHERE-выражения через {@link ExpressionCompiler}.
 * <p>
 * Все ошибки ядра ({@link main.core.domainExceptions.DatabaseException})
 * и ошибки парсинга ({@link main.query.ParseException}) пробрасываются наверх
 * без изменений — UI различает их по типу.
 */
public class CommandExecutor {

    private final DbCommandApi api;

    public CommandExecutor(DbCommandApi api) {
        this.api = api;
    }

    /** Точка входа для UI: строка -> результат. */
    public Object execute(String sql) {
        return execute(Parser.parse(sql));
    }

    public Object execute(Ast.Statement stmt) {
        return switch (stmt) {
            case Ast.CreateTable ct -> { createTable(ct); yield null; }
            case Ast.CreateIndex ci -> { createIndex(ci); yield null; }
            case Ast.DropTable dt    -> { dropTable(dt);    yield null; }
            case Ast.DropIndex di    -> { dropIndex(di);    yield null; }
            case Ast.Insert ins      -> { insert(ins);      yield null; }
            case Ast.Select sel      -> select(sel);
            case Ast.Update upd      -> update(upd);
            case Ast.Delete del      -> delete(del);
            default -> throw new CommandException(
                    "Неподдерживаемый тип команды: " + stmt.getClass().getSimpleName());
        };
    }

    // ---------- DDL ----------

    private void createTable(Ast.CreateTable ct) {
        if (ct.columns().isEmpty()) {
            throw new CommandException("Нужен хотя бы один столбец");
        }
        ColumnDef[] cols = new ColumnDef[ct.columns().size()];
        for (int i = 0; i < cols.length; i++) {
            Ast.ColumnDef cd = ct.columns().get(i);
            ColumnType type = parseType(cd.type());
            boolean isPk = (i == 0);
            // PK: NOT NULL + UNIQUE. Остальные: nullable, не unique (если не задано иное в DDL).
            cols[i] = new ColumnDef(cd.name(), type, !isPk, isPk);
        }
        TableSchema schema = new TableSchema(ct.name(), cols);
        // Внешние ключи парсер пока не поддерживает — передаём null.
        api.createTable(ct.name(), schema, null);
    }

    private void createIndex(Ast.CreateIndex ci) {
        api.createIndex(ci.table(), ci.column());
    }

    private void dropTable(Ast.DropTable dt) {
        api.dropTable(dt.name());
    }

    private void dropIndex(Ast.DropIndex di) {
        // В DbCommandApi/Table/Database нет метода dropIndex — заглушка.
        throw new CommandException("DROP INDEX не поддерживается ядром");
    }

    // ---------- DML ----------

    private void insert(Ast.Insert ins) {
        TableSchema schema = api.getSchema(ins.table());
        ColumnDef[] cols = schema.getColumns();

        Map<String, Object> row = new LinkedHashMap<>();
        if (ins.columns().isEmpty()) {
            if (ins.values().size() != cols.length) {
                throw new AmountValuesException(cols.length);
            }
            for (int i = 0; i < cols.length; i++) {
                row.put(cols[i].name(), convert(cols[i].type(), ins.values().get(i).value(), cols[i].name()));
            }
        } else {
            if (ins.columns().size() != ins.values().size()) {
                throw new CommandException("Число столбцов и значений не совпадает");
            }
            for (int i = 0; i < ins.columns().size(); i++) {
                String colName = ins.columns().get(i);
                ColumnDef def = schema.getColumn(colName); // UnknownColumnName, если нет
                row.put(colName, convert(def.type(), ins.values().get(i).value(), colName));
            }
        }
        api.insert(ins.table(), row);
    }

    private Object select(Ast.Select sel) {
        TableSchema schema = api.getSchema(sel.table());

        if (sel.columns().isEmpty()) {
            // SELECT * -> List<Object[]>
            return api.getTable(sel.table());
        }

        String[] finding = sel.columns().toArray(new String[0]);
        for (String c : finding) schema.getColumn(c); // валидация -> UnknownColumnName

        if (sel.where() == null) {
            return api.selectItems(sel.table(), finding, new String[0], null);
        }
        ExpressionCompiler.Compiled compiled = ExpressionCompiler.compile(schema, sel.where());
        return api.selectItems(sel.table(), finding,
                compiled.conditionCols(), compiled.predicate());
    }

    private long update(Ast.Update upd) {
        TableSchema schema = api.getSchema(upd.table());
        Map<String, Object> newValues = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Literal> e : upd.assignments().entrySet()) {
            ColumnDef def = schema.getColumn(e.getKey());
            newValues.put(e.getKey(), convert(def.type(), e.getValue().value(), e.getKey()));
        }
        if (upd.where() == null) {
            return api.updateItems(upd.table(), newValues, new String[0], null);
        }
        ExpressionCompiler.Compiled compiled = ExpressionCompiler.compile(schema, upd.where());
        return api.updateItems(upd.table(), newValues,
                compiled.conditionCols(), compiled.predicate());
    }

    private long delete(Ast.Delete del) {
        TableSchema schema = api.getSchema(del.table());
        if (del.where() == null) {
            return api.deleteItems(del.table(), new String[0], null);
        }
        ExpressionCompiler.Compiled compiled = ExpressionCompiler.compile(schema, del.where());
        return api.deleteItems(del.table(),
                compiled.conditionCols(), compiled.predicate());
    }

    private static ColumnType parseType(String s) {
        try {
            return ColumnType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CommandException("Неизвестный тип столбца: " + s);
        }
    }

    /** Приводит литерал из AST к представлению, ожидаемому ColumnType. */
    private static Object convert(ColumnType type, Object literal, String colName) {
        if (literal == null) return null;
        try {
            return type.requireType(literal);
        } catch (RuntimeException e) {
            throw new IncorrectType(type.name(), colName);
        }
    }
}