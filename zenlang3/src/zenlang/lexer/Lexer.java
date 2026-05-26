package zenlang.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer for Zen-Lang v3.
 * New tokens: DOT (.), COLON (:), block comments (/* ... *‌/)
 */
public class Lexer {

    private final String source;
    private int pos, line, column;

    public Lexer(String source) {
        this.source = source;
        this.pos    = 0;
        this.line   = 1;
        this.column = 1;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do { t = nextToken(); tokens.add(t); }
        while (t.type != TokenType.END_OF_FILE);
        return tokens;
    }

    // ── char helpers ────────────────────────────────────────────────────────

    private char peek()     { return pos < source.length() ? source.charAt(pos) : '\0'; }
    private char peekNext() { return (pos+1) < source.length() ? source.charAt(pos+1) : '\0'; }

    private char get() {
        if (pos >= source.length()) return '\0';
        char c = source.charAt(pos++);
        if (c == '\n') { line++; column = 1; } else { column++; }
        return c;
    }

    private void skipWS() { while (Character.isWhitespace(peek())) get(); }

    // ── main token ──────────────────────────────────────────────────────────

    private Token nextToken() {
        skipWS();
        int sl = line, sc = column;
        char c = peek();

        if (c == '\0') return tok(TokenType.END_OF_FILE, "", sl, sc);

        // Header  #use <...>
        if (c == '#') {
            StringBuilder sb = new StringBuilder();
            while (peek() != '\0' && peek() != '\n') sb.append(get());
            return tok(TokenType.HEADER, sb.toString(), sl, sc);
        }

        // Line comment  //
        if (c == '/' && peekNext() == '/') {
            while (peek() != '\n' && peek() != '\0') get();
            return nextToken();
        }
        // Block comment  /* ... */
        if (c == '/' && peekNext() == '*') {
            get(); get();
            while (!(peek() == '*' && peekNext() == '/')) {
                if (peek() == '\0') throw err("Unterminated block comment", sl, sc);
                get();
            }
            get(); get();
            return nextToken();
        }

        // Identifiers / keywords
        if (Character.isLetter(c) || c == '_') {
            StringBuilder sb = new StringBuilder();
            while (Character.isLetterOrDigit(peek()) || peek() == '_') sb.append(get());
            String word = sb.toString();
            return tok(Tokens.KEYWORDS.contains(word) ? TokenType.KEYWORD : TokenType.IDENTIFIER,
                       word, sl, sc);
        }

        // Numbers
        if (Character.isDigit(c)) {
            StringBuilder sb = new StringBuilder();
            boolean dec = false;
            while (Character.isDigit(peek()) || (peek() == '.' && !dec && Character.isDigit(peekNext()))) {
                if (peek() == '.') dec = true;
                sb.append(get());
            }
            return tok(dec ? TokenType.DECIMAL : TokenType.NUMBER, sb.toString(), sl, sc);
        }

        // Strings
        if (c == '"') {
            get();
            StringBuilder sb = new StringBuilder();
            while (peek() != '"' && peek() != '\0') {
                if (peek() == '\\') { get(); sb.append(esc(get())); }
                else sb.append(get());
            }
            get();
            return tok(TokenType.STRING, sb.toString(), sl, sc);
        }

        // ── operators ────────────────────────────────────────────────────────
        if (c == '+') { get();
            if (peek() == '+') { get(); return tok(TokenType.OPERATOR, "++", sl, sc); }
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "+=", sl, sc); }
            return tok(TokenType.OPERATOR, "+", sl, sc);
        }
        if (c == '-') { get();
            if (peek() == '-') { get(); return tok(TokenType.OPERATOR, "--", sl, sc); }
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "-=", sl, sc); }
            return tok(TokenType.OPERATOR, "-", sl, sc);
        }
        if (c == '*') { get();
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "*=", sl, sc); }
            return tok(TokenType.OPERATOR, "*", sl, sc);
        }
        if (c == '/') { get();
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "/=", sl, sc); }
            return tok(TokenType.OPERATOR, "/", sl, sc);
        }
        if (c == '%') { get(); return tok(TokenType.OPERATOR, "%", sl, sc); }

        if (c == '=') { get();
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "==", sl, sc); }
            return tok(TokenType.ASSIGN, "=", sl, sc);
        }
        if (c == '!') { get();
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "!=", sl, sc); }
            return tok(TokenType.OPERATOR, "!", sl, sc);
        }
        if (c == '<') { get();
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, "<=", sl, sc); }
            return tok(TokenType.OPERATOR, "<", sl, sc);
        }
        if (c == '>') { get();
            if (peek() == '=') { get(); return tok(TokenType.OPERATOR, ">=", sl, sc); }
            return tok(TokenType.OPERATOR, ">", sl, sc);
        }
        if (c == '&' && peekNext() == '&') { get(); get(); return tok(TokenType.OPERATOR, "&&", sl, sc); }
        if (c == '|' && peekNext() == '|') { get(); get(); return tok(TokenType.OPERATOR, "||", sl, sc); }

        // ── punctuation ──────────────────────────────────────────────────────
        switch (c) {
            case '(': get(); return tok(TokenType.LPAREN,   "(", sl, sc);
            case ')': get(); return tok(TokenType.RPAREN,   ")", sl, sc);
            case '[': get(); return tok(TokenType.LBRACKET, "[", sl, sc);
            case ']': get(); return tok(TokenType.RBRACKET, "]", sl, sc);
            case '{': get(); return tok(TokenType.LBRACE,   "{", sl, sc);
            case '}': get(); return tok(TokenType.RBRACE,   "}", sl, sc);
            case ',': get(); return tok(TokenType.COMMA,    ",", sl, sc);
            case ';': get(); return tok(TokenType.OPERATOR, ";", sl, sc);
            case '.': get(); return tok(TokenType.DOT,      ".", sl, sc);
            case ':': get(); return tok(TokenType.COLON,    ":", sl, sc);
        }

        get();
        return tok(TokenType.UNKNOWN, String.valueOf(c), sl, sc);
    }

    private static Token tok(TokenType t, String v, int l, int c) { return new Token(t, v, l, c); }

    private static char esc(char c) {
        return switch (c) {
            case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
            case '\\' -> '\\'; case '"' -> '"';
            default -> c;
        };
    }

    private RuntimeException err(String msg, int l, int c) {
        return new RuntimeException("[Lexer Error] " + msg + " at " + l + ":" + c);
    }
}
