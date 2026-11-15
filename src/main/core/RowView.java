package main.core;

import main.core.domainExceptions.IncorrectTypeInConditionException;

public class RowView {
    private final Object[] values;
    private final ColumnType[] types;
    private final String[] colNames;

    public RowView(Object[] values, ColumnType[] types, String[] colNames) {
        this.values = values;
        this.types = types;
        this.colNames = colNames;
    }

    public boolean eq(int index, Object target) {
        try {
            return types[index].eq(values[index], target);
        } catch (RuntimeException e) {
            throw new IncorrectTypeInConditionException(colNames[index]);
        }
    }

    public boolean gt(int index, Object target) {
        try {
            return types[index].gt(values[index], target);
        } catch (RuntimeException e) {
            throw new IncorrectTypeInConditionException(colNames[index]);
        }
    }

    public boolean lt(int index, Object target) {
        try {
            return types[index].lt(values[index], target);
        } catch (RuntimeException e) {
            throw new IncorrectTypeInConditionException(colNames[index]);
        }
    }

    public boolean isNull(int index) {
        return values[index] == null;
    }
}
