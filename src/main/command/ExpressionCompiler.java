package main.command;

import main.core.ColumnType;
import main.core.RowView;
import main.core.TableSchema;
import main.core.domainExceptions.IncorrectTypeInConditionException;
import main.query.Ast;
import main.query.TokenType;

import java.util.LinkedHashMap;
import java.util.function.Predicate;

/**
 * Компилирует AST-выражение WHERE в пару (conditionCols, Predicate<RowView>),
 * совместимую с DbCommandApi.selectItems/updateItems/deleteItems.
 * <p>
 * Валидация типов литералов делегируется {@link RowView}: его методы
 * {@code eq/gt/lt} уже оборачивают {@link IllegalArgumentException} в
 * {@link IncorrectTypeInConditionException}. Здесь проверяется только
 * существование столбцов (через {@link TableSchema#getColumn}), чтобы ошибка
 * была понятной до выполнения запроса.
 */
public final class ExpressionCompiler {

    public record Compiled(String[] conditionCols, Predicate<RowView> predicate) {}

    private final TableSchema schema;
    /** Уникальные имена столбцов в порядке первого появления. */
    private final LinkedHashMap<String, Integer> colIndex = new LinkedHashMap<>();

    private ExpressionCompiler(TableSchema schema) {
        this.schema = schema;
    }

    public static Compiled compile(TableSchema schema, Ast.Expr expr) {
        ExpressionCompiler c = new ExpressionCompiler(schema);
        Predicate<RowView> p = c.build(expr);
        return new Compiled(c.colIndex.keySet().toArray(new String[0]), p);
    }

    private Predicate<RowView> build(Ast.Expr e) {
        if (e instanceof Ast.Logical l)    return combine(l);
        if (e instanceof Ast.Not n)        return build(n.operand()).negate();
        if (e instanceof Ast.Comparison c) return compare(c);
        if (e instanceof Ast.Column col) {
            throw new CommandException("Столбец '" + col.name() + "' недопустим как условие");
        }
        if (e instanceof Ast.Literal lit && lit.value() instanceof Boolean b) {
            return rv -> b;
        }
        throw new CommandException("Неизвестное выражение: " + e);
    }

    private Predicate<RowView> combine(Ast.Logical l) {
        Predicate<RowView> left  = build(l.left());
        Predicate<RowView> right = build(l.right());
        return switch (l.op()) {
            case AND -> left.and(right);
            case OR  -> left.or(right);
            default  -> throw new CommandException("Ожидался AND/OR, получено " + l.op());
        };
    }

    private Predicate<RowView> compare(Ast.Comparison cmp) {
        String colName;
        Object literal;
        boolean reversed = false;

        if (cmp.left() instanceof Ast.Column c && cmp.right() instanceof Ast.Literal lit) {
            colName = c.name();
            literal = lit.value();
        } else if (cmp.left() instanceof Ast.Literal lit && cmp.right() instanceof Ast.Column c) {
            colName = c.name();
            literal = lit.value();
            reversed = true;
        } else {
            throw new CommandException("Сравнение должно быть вида column OP literal");
        }

        ColumnType type = schema.getColumn(colName).type(); // UnknownColumnName, если нет

        // Нормализуем литерал к представлению типа, чтобы RowView.compareValues
        // не спотыкался о несовместимые Java-классы (Long vs Integer для INT,
        // String vs LocalDate для DATEDAYS). Ошибка типа -> IncorrectTypeInConditionException.
        Object converted;
        try {
            converted = type.requireType(literal);
        } catch (RuntimeException ex) {
            throw new IncorrectTypeInConditionException(colName);
        }

        int idx = colIndex.computeIfAbsent(colName, k -> colIndex.size());
        TokenType op = reversed ? flip(cmp.op()) : cmp.op();

        return rv -> switch (op) {
            case EQUALS -> rv.eq(idx, converted);
            case NEQ    -> !rv.eq(idx, converted);
            case GT     -> rv.gt(idx, converted);
            case LT     -> rv.lt(idx, converted);
            case GE     -> rv.gt(idx, converted) || rv.eq(idx, converted);
            case LE     -> rv.lt(idx, converted) || rv.eq(idx, converted);
            default -> throw new CommandException("Неизвестный оператор " + op);
        };
    }

    private static TokenType flip(TokenType op) {
        return switch (op) {
            case LT -> TokenType.GT;
            case GT -> TokenType.LT;
            case LE -> TokenType.GE;
            case GE -> TokenType.LE;
            default -> op; // EQ, NEQ симметричны
        };
    }
}