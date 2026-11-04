package query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lexer {
    private final String src;
    private int pos = 0;

    public Lexer(String src) {
        this.src = src;
    }

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();
    static {
        for (TokenType type : TokenType.values()) {
            if (    type != TokenType.IDENTIFIER && type != TokenType.NUMBER &&
                    type != TokenType.STRING && type != TokenType.COMMA &&
                    type != TokenType.ASTERISK && type != TokenType.EQUALS &&
                    type != TokenType.OPEN_PAREN && type != TokenType.CLOSE_PAREN &&
                    type != TokenType.EOF) {
                KEYWORDS.put(type.name(), type);
            }
        }
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (pos < src.length()) {
            char current = src.charAt(pos);

            if (Character.isWhitespace(current)) {
                pos++;
                continue;
            }

            int tokenStartPos = pos;

            if (Character.isLetter(current) || current == '_') {
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    sb.append(src.charAt(pos));
                    pos++;
                }

                String word = sb.toString();
                String upperWord = word.toUpperCase();

                Token token;
                if (upperWord.equals("TRUE") || upperWord.equals("FALSE")) {
                    token = new Token(TokenType.BOOLEAN, word);
                }
                else if (KEYWORDS.containsKey(upperWord)) {
                    token = new Token(KEYWORDS.get(upperWord), word);
                } else {
                    token = new Token(TokenType.IDENTIFIER, word);
                }

                token.position = tokenStartPos;
                tokens.add(token);
                continue;
            }

            if (current == '\'') {
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && src.charAt(pos) != '\'') {
                    sb.append(src.charAt(pos));
                    pos++;
                }

                if (pos >= src.length()) {
                    throw new RuntimeException("Unterminated string literal at position " + tokenStartPos);
                }
                pos++;

                String word = sb.toString();
                Token token = new Token(TokenType.STRING, word);
                token.position = tokenStartPos;
                tokens.add(token);
                continue;

            }

            if (Character.isDigit(current)) {
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    sb.append(src.charAt(pos));
                    pos++;
                }

                String word = sb.toString();
                Token token = new Token(TokenType.NUMBER, word);
                token.position = tokenStartPos;
                tokens.add(token);
                continue;

            }

            Token token = null;

            if (current == '<' || current == '>' || current == '!') {
                char next = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
                TokenType type;
                String text;
                if (current == '<' && next == '=')      { type = TokenType.LE;  text = "<="; }
                else if (current == '<' && next == '>') { type = TokenType.NEQ; text = "<>"; }
                else if (current == '<')                { type = TokenType.LT;  text = "<"; }
                else if (current == '>' && next == '=') { type = TokenType.GE;  text = ">="; }
                else if (current == '>')                { type = TokenType.GT;  text = ">"; }
                else if (next == '=')                   { type = TokenType.NEQ; text = "!="; }
                else throw new RuntimeException("Unknown character '!' at position " + pos);

                token = new Token(type, text);
                token.position = tokenStartPos;
                tokens.add(token);
                pos += text.length();
                continue;
            }

            switch (current) {
                case ',' -> token = new Token(TokenType.COMMA, ",");
                case '*' -> token = new Token(TokenType.ASTERISK, "*");
                case '=' -> token = new Token(TokenType.EQUALS, "=");
                case '(' -> token = new Token(TokenType.OPEN_PAREN, "(");
                case ')' -> token = new Token(TokenType.CLOSE_PAREN, ")");
            }

            if (token != null) {
                token.position = tokenStartPos;
                tokens.add(token);
                pos++;
                continue;
            }

            throw new RuntimeException("Unknown character '" + current + "' at position " + pos);
        }
        Token eofToken = new Token(TokenType.EOF, "");
        eofToken.position = pos;
        tokens.add(eofToken);

        return tokens;
    }
}
