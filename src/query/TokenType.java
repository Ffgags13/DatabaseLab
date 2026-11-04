package query;

public enum TokenType {
    INSERT, INTO, VALUES,
    SELECT, FROM, WHERE,
    UPDATE, SET,
    DELETE,
    CREATE, TABLE, INDEX, DROP, ON,
    AND, OR, NOT,

    IDENTIFIER, BOOLEAN, NUMBER,
    STRING,

    EQ, NEQ, LT, GT, LE, GE,

    COMMA,
    ASTERISK,
    EQUALS,
    OPEN_PAREN,
    CLOSE_PAREN,

    EOF
}
