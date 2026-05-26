package zenlang.interpreter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.*;

/**
 * Runtime value for Zen-Lang v3.
 *
 * Kinds:
 *   NUMBER      – double
 *   STRING      – String
 *   ARRAY       – List<Value>  (mutable, shared by reference)
 *   MAP         – LinkedHashMap<String,Value>  (mutable, ordered)
 *   STRUCT      – StructInstance (typed mutable field map)
 *   ENUM_VAL    – EnumVal (enumName, valueName, ordinal)
 *   FILE_HANDLE – FileHandle (wraps Java BufferedReader/Writer)
 *   NULL        – the null literal
 */
public final class Value {

    // ── Kinds ────────────────────────────────────────────────────────────────

    public enum Kind { NUMBER, STRING, ARRAY, MAP, STRUCT, ENUM_VAL, FILE_HANDLE, NULL }

    // ── Inner types ──────────────────────────────────────────────────────────

    public static class StructInstance {
        public final String typeName;
        public final Map<String, Value> fields = new LinkedHashMap<>();
        public StructInstance(String typeName) { this.typeName = typeName; }
    }

    public static class EnumVal {
        public final String enumName, valueName;
        public final int    ordinal;
        public EnumVal(String en, String vn, int ord) {
            enumName = en; valueName = vn; ordinal = ord;
        }
    }

    public static class FileHandle {
        public final String path, mode;
        public BufferedReader reader;
        public BufferedWriter writer;
        public boolean closed = false;
        public FileHandle(String path, String mode) { this.path=path; this.mode=mode; }
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Kind   kind;
    private final double number;   // NUMBER
    private final Object payload;  // STRING / List<Value> / Map / StructInstance / EnumVal / FileHandle

    // ── Factories ────────────────────────────────────────────────────────────

    public static Value ofNumber(double n)          { return new Value(Kind.NUMBER,      n,   null);      }
    public static Value ofString(String s)          { return new Value(Kind.STRING,      0,   s);         }
    public static Value ofArray(List<Value> a)      { return new Value(Kind.ARRAY,       0,   a);         }
    public static Value ofMap(Map<String,Value> m)  { return new Value(Kind.MAP,         0,   m);         }
    public static Value ofStruct(StructInstance s)  { return new Value(Kind.STRUCT,      0,   s);         }
    public static Value ofEnum(EnumVal e)           { return new Value(Kind.ENUM_VAL,    0,   e);         }
    public static Value ofFile(FileHandle f)        { return new Value(Kind.FILE_HANDLE, 0,   f);         }
    public static final Value NULL = new Value(Kind.NULL, 0, null);

    private Value(Kind kind, double number, Object payload) {
        this.kind = kind; this.number = number; this.payload = payload;
    }

    // ── Type checks ──────────────────────────────────────────────────────────

    public Kind    kind()     { return kind;               }
    public boolean isNumber() { return kind == Kind.NUMBER; }
    public boolean isString() { return kind == Kind.STRING; }
    public boolean isArray()  { return kind == Kind.ARRAY;  }
    public boolean isMap()    { return kind == Kind.MAP;    }
    public boolean isStruct() { return kind == Kind.STRUCT; }
    public boolean isEnum()   { return kind == Kind.ENUM_VAL; }
    public boolean isFile()   { return kind == Kind.FILE_HANDLE; }
    public boolean isNull()   { return kind == Kind.NULL;   }

    // ── Typed accessors ──────────────────────────────────────────────────────

    public double asNumber() {
        if (!isNumber()) throw new RuntimeException("[Type Error] Expected number, got " + kind);
        return number;
    }
    public String asString() {
        if (!isString()) throw new RuntimeException("[Type Error] Expected string, got " + kind);
        return (String) payload;
    }
    @SuppressWarnings("unchecked")
    public List<Value> asArray() {
        if (!isArray()) throw new RuntimeException("[Type Error] Expected array, got " + kind);
        return (List<Value>) payload;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Value> asMap() {
        if (!isMap()) throw new RuntimeException("[Type Error] Expected map, got " + kind);
        return (Map<String,Value>) payload;
    }
    public StructInstance asStruct() {
        if (!isStruct()) throw new RuntimeException("[Type Error] Expected struct, got " + kind);
        return (StructInstance) payload;
    }
    public EnumVal asEnum() {
        if (!isEnum()) throw new RuntimeException("[Type Error] Expected enum value, got " + kind);
        return (EnumVal) payload;
    }
    public FileHandle asFile() {
        if (!isFile()) throw new RuntimeException("[Type Error] Expected file handle, got " + kind);
        return (FileHandle) payload;
    }

    // ── Truthiness ───────────────────────────────────────────────────────────

    public boolean isTruthy() {
        return switch (kind) {
            case NUMBER      -> number != 0.0;
            case STRING      -> !((String) payload).isEmpty();
            case ARRAY       -> !asArray().isEmpty();
            case MAP         -> !asMap().isEmpty();
            case STRUCT, ENUM_VAL, FILE_HANDLE -> true;
            case NULL        -> false;
        };
    }

    // ── Display ──────────────────────────────────────────────────────────────

    public String display() {
        return switch (kind) {
            case NULL        -> "null";
            case NUMBER      -> {
                if (number == Math.floor(number) && !Double.isInfinite(number))
                    yield String.valueOf((long) number);
                yield String.valueOf(number);
            }
            case STRING      -> (String) payload;
            case ARRAY       -> {
                StringBuilder sb = new StringBuilder("[");
                List<Value> arr = asArray();
                for (int i = 0; i < arr.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(arr.get(i).display());
                }
                sb.append("]");
                yield sb.toString();
            }
            case MAP         -> {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (var entry : asMap().entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append('"').append(entry.getKey()).append("\": ");
                    sb.append(entry.getValue().display());
                    first = false;
                }
                sb.append("}");
                yield sb.toString();
            }
            case STRUCT      -> {
                StructInstance si = asStruct();
                StringBuilder sb = new StringBuilder(si.typeName + " {");
                boolean first = true;
                for (var e : si.fields.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append(": ").append(e.getValue().display());
                    first = false;
                }
                sb.append("}");
                yield sb.toString();
            }
            case ENUM_VAL    -> {
                EnumVal ev = asEnum();
                yield ev.enumName + "." + ev.valueName;
            }
            case FILE_HANDLE -> {
                FileHandle fh = asFile();
                yield "File<" + fh.path + "," + fh.mode + (fh.closed ? ",closed" : "") + ">";
            }
        };
    }

    @Override public String toString() { return display(); }
}
