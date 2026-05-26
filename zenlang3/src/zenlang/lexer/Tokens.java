package zenlang.lexer;

import java.util.Set;

public final class Tokens {
    private Tokens() {}

    public static final Set<String> KEYWORDS = Set.of(
        // ── Type keywords ──────────────────────────────────────────────────
        "int", "float", "string", "bool", "array", "map", "file", "void",
        // ── Control flow ───────────────────────────────────────────────────
        "if", "else", "while", "for", "foreach", "in",
        "switch", "case", "default",
        "return", "break", "continue",
        // ── OOP ────────────────────────────────────────────────────────────
        "struct", "this",
        // ── Declarations ───────────────────────────────────────────────────
        "const", "enum", "import",
        // ── Literals ───────────────────────────────────────────────────────
        "true", "false", "null",
        // ── Range / step ───────────────────────────────────────────────────
        "to", "step"
    );

    /** Returns true iff the word can appear as a variable / param type. */
    public static boolean isTypeKeyword(String word) {
        return switch (word) {
            case "int","float","string","bool","array","map","file","void" -> true;
            default -> false;
        };
    }
}
