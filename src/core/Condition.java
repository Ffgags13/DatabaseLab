package core;

/**
 * Условие WHERE — дерево из {@link Eq}, {@link Gt}, ... и композиций
 * {@link And}/{@link Or}. По нему {@link Table} решает, использовать ли индекс.
 * <p>
 * Все реализации — вложенные record, никаких внешних реализаций.
 */
public sealed interface Condition {

    record Eq(String column, Object value) implements Condition {}
    record Gt(String column, Object value) implements Condition {}
    record Ge(String column, Object value) implements Condition {}
    record Lt(String column, Object value) implements Condition {}
    record Le(String column, Object value) implements Condition {}

    record And(Condition left, Condition right) implements Condition {}
    record Or(Condition left, Condition right)  implements Condition {}
}