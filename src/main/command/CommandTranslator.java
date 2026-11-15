package main.command;

import main.core.Catalog;
import main.core.ColumnType;
import main.core.Condition;
import main.core.TableSchema;
import main.index.IndexDefinition;
import main.index.IndexKind;
import main.query.Ast.*;
import main.query.TokenType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CommandTranslator {

    private final Catalog catalog;

    public CommandTranslator(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Command translate(Statement stmt) {
        return switch (stmt) {
            case CreateTable ct -> translateCreateTable(ct);
            case DropTable dt   -> new Command.DropTable(dt.name());
            case CreateIndex ci -> translateCreateIndex(ci);
            case DropIndex di   -> translateDropIndex(di);
            case Insert ins     -> translateInsert(ins);
            case Update upd     -> translateUpdate(upd);
            case Delete del     -> translateDelete(del);
            case Select sel     -> translateSelect(sel);
            default -> throw new IllegalArgumentException("Unknown statement: " + stmt);
        };
    }

    private Command translateCreateTable(CreateTable ct) {
        List<main.core.ColumnDef> cols = new ArrayList<>(ct.columns().size());
        for (int i = 0; i < ct.columns().size(); i++) {
            main.query.Ast.ColumnDef astCol = ct.columns().get(i);
            ColumnType type = ColumnType.valueOf(astCol.type().toUpperCase());
            cols.add(i == 0
                    ? main.core.ColumnDef.primaryKey(astCol.name(), type)
                    : main.core.ColumnDef.plain(astCol.name(), type));
        }
        return new Command.CreateTable(ct.name(), cols);
    }

    private Command translateCreateIndex(CreateIndex ci) {
        return new Command.CreateIndex(ci.table(), ci.column(), IndexKind.HASH, false);
    }

    private Command translateDropIndex(DropIndex di) {
        String idxName = di.name();
        for (String table : catalog.listTableNames()) {
            for (IndexDefinition def : catalog.getIndexDefinitions(table)) {
                if (def.column().equals(idxName)) {
                    return new Command.DropIndex(table, idxName);
                }
            }
        }
        throw new IllegalArgumentException("Индекс не найден: " + idxName);
    }

    private Command translateInsert(Insert ins) {
        TableSchema schema = catalog.getSchema(ins.table());
        main.core.ColumnDef[] all = schema.getColumns();

        Map<String, Object> row = new LinkedHashMap<>();

        if (ins.columns().isEmpty()) {
            if (ins.values().size() != all.length) {
                throw new IllegalArgumentException(
                        "Ожидалось " + all.length + " значений, получено " + ins.values().size());
            }
            for (int i = 0; i < all.length; i++) {
                row.put(all[i].name(), coerce(ins.values().get(i), all[i]));
            }
        } else {
            for (int i = 0; i < ins.columns().size(); i++) {
                main.core.ColumnDef col = schema.getColumn(ins.columns().get(i));
                row.put(col.name(), coerce(ins.values().get(i), col));
            }
        }
        return new Command.Insert(ins.table(), row);
    }

    private Command translateUpdate(Update upd) {
        TableSchema schema = catalog.getSchema(upd.table());
        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<String, Literal> e : upd.assignments().entrySet()) {
            main.core.ColumnDef col = schema.getColumn(e.getKey());
            patch.put(col.name(), coerce(e.getValue(), col));
        }
        Condition where = upd.where() == null ? null : toCondition(upd.where(), schema);
        return new Command.Update(upd.table(), patch, where);
    }

    private Command translateDelete(Delete del) {
        TableSchema schema = catalog.getSchema(del.table());
        Condition where = del.where() == null ? null : toCondition(del.where(), schema);
        return new Command.Delete(del.table(), where);
    }

    private Command translateSelect(Select sel) {
        TableSchema schema = catalog.getSchema(sel.table());
        for (String c : sel.columns()) schema.getColumn(c);
        Condition where = sel.where() == null ? null : toCondition(sel.where(), schema);
        return new Command.Select(sel.table(), sel.columns(), where);
    }

    private Condition toCondition(Expr e, TableSchema schema) {
        return switch (e) {
            case Logical l -> l.op() == TokenType.AND
                    ? new Condition.And(toCondition(l.left(), schema),
                    toCondition(l.right(), schema))
                    : new Condition.Or (toCondition(l.left(), schema),
                    toCondition(l.right(), schema));
            case Not n -> negate(toCondition(n.operand(), schema));
            case Comparison c -> toComparison(c, schema);
            default -> throw new IllegalArgumentException(
                    "Выражение не является условием: " + e);
        };
    }

    private Condition toComparison(Comparison c, TableSchema schema) {
        String colName;
        Object value;
        TokenType op = c.op();

        if (c.left() instanceof Column col && c.right() instanceof Literal lit) {
            colName = col.name();
            value = coerce(lit, schema.getColumn(colName));
        } else if (c.right() instanceof Column col && c.left() instanceof Literal lit) {
            colName = col.name();
            value = coerce(lit, schema.getColumn(colName));
            op = flip(op);
        } else {
            throw new IllegalArgumentException(
                    "Сравнение должно быть колонка-литерал: " + c);
        }

        return switch (op) {
            case EQUALS -> new Condition.Eq(colName, value);
            case NEQ    -> negate(new Condition.Eq(colName, value));
            case LT     -> new Condition.Lt(colName, value);
            case LE     -> new Condition.Le(colName, value);
            case GT     -> new Condition.Gt(colName, value);
            case GE     -> new Condition.Ge(colName, value);
            default -> throw new IllegalArgumentException("Не оператор сравнения: " + op);
        };
    }

    private static TokenType flip(TokenType op) {
        return switch (op) {
            case LT -> TokenType.GT;
            case LE -> TokenType.GE;
            case GT -> TokenType.LT;
            case GE -> TokenType.LE;
            default -> op;
        };
    }

    private Condition negate(Condition c) {
        return switch (c) {
            case Condition.Eq eq -> new Condition.Or(
                    new Condition.Lt(eq.column(), eq.value()),
                    new Condition.Gt(eq.column(), eq.value()));
            case Condition.Gt gt -> new Condition.Le(gt.column(), gt.value());
            case Condition.Ge ge -> new Condition.Lt(ge.column(), ge.value());
            case Condition.Lt lt -> new Condition.Ge(lt.column(), lt.value());
            case Condition.Le le -> new Condition.Gt(le.column(), le.value());
            case Condition.And a -> new Condition.Or(negate(a.left()), negate(a.right()));
            case Condition.Or  o -> new Condition.And(negate(o.left()), negate(o.right()));
        };
    }

    private Object coerce(Literal lit, main.core.ColumnDef col) {
        Object v = lit.value();
        return switch (col.type()) {
            case INT -> {
                if (v instanceof Long l)     yield Math.toIntExact(l);
                if (v instanceof Integer i)  yield i;
                throw typeErr(col, v);
            }
            case DOUBLE -> {
                if (v instanceof Long l)    yield l.doubleValue();
                if (v instanceof Double d)  yield d;
                if (v instanceof Integer i) yield i.doubleValue();
                throw typeErr(col, v);
            }
            case BOOLEAN -> {
                if (v instanceof Boolean b) yield b;
                throw typeErr(col, v);
            }
            case STRING -> {
                if (v instanceof String s) yield s;
                throw typeErr(col, v);
            }
            case DATE -> {
                if (v instanceof String s) yield LocalDate.parse(s);
                if (v instanceof Long l)   yield LocalDate.ofEpochDay(l);
                throw typeErr(col, v);
            }
        };
    }

    private static IllegalArgumentException typeErr(main.core.ColumnDef col, Object v) {
        return new IllegalArgumentException(
                "Колонка «" + col.name() + "» ожидает " + col.type()
                        + ", получено " + (v == null ? "null" : v.getClass().getSimpleName()));
    }
}