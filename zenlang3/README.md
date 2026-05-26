# Zen-Lang v3 — Language Reference

## Build & Run (Java 17 / Windows PowerShell)

```powershell
# Compile
$files = Get-ChildItem -Recurse -Path src -Filter "*.java" | Select-Object -ExpandProperty FullName
javac -d out $files

# Run
java -cp out zenlang.Main demo.zen
```

---

## New in v3

| Feature | Syntax |
|---|---|
| Struct / Object | `struct Name { fields; constructor; methods }` |
| Map / Dict | `map m = {"key": value};` |
| Foreach | `foreach (type var in iterable) { }` |
| Switch | `switch (expr) { case val: { } default: { } }` |
| Const | `const int MAX = 100;` |
| Enum | `enum Name { VAL1, VAL2 }` |
| Import | `import "file.zen";` |
| File I/O | `open()`, `readLine()`, `readAll()`, `write()`, `close()` |

---

## 1. Structs

```
struct Point {
    float x;
    float y;

    // Constructor
    Point(float x, float y) {
        this.x = x;
        this.y = y;
    }

    // Method
    float distance() {
        return sqrt(this.x * this.x + this.y * this.y);
    }

    // Void method (no return value)
    void move(float dx, float dy) {
        this.x = this.x + dx;
        this.y = this.y + dy;
    }
}

Point p = Point(3.0, 4.0);
println(p.x);               // field access
println(p.distance());      // method call
p.move(1.0, 0.0);          // mutation via method
```

- Fields are declared with types inside the struct body
- The constructor has the same name as the struct
- Use `this.fieldName` to access fields inside methods
- No `new` keyword — instantiate like a function call: `Point(x, y)`
- Methods can read and mutate `this`

---

## 2. Map / Dictionary

```
map ages = {"Alice": 25, "Bob": 30, "Charlie": 22};

// Access
println(ages["Alice"]);     // 25

// Set / update
ages["Dave"] = 28;

// Map methods
ages.size()                 // → 4
ages.keys()                 // → ["Alice", "Bob", "Charlie", "Dave"]
ages.values()               // → [25, 30, 22, 28]
ages.containsKey("Bob")     // → 1 (true)
ages.remove("Bob")

// Iterate keys with foreach
foreach (string name in ages) {
    println(name + " is " + ages[name]);
}
```

---

## 3. Foreach

```
// Over array
array nums = [1, 2, 3, 4, 5];
foreach (int n in nums) {
    println(n);
}

// Over map (iterates keys)
map data = {"a": 1, "b": 2};
foreach (string key in data) {
    println(key + ": " + data[key]);
}
```

- `break` and `continue` work inside foreach
- The variable type is just a hint (not enforced at runtime)

---

## 4. Switch

```
switch (x) {
    case 1: { println("one");   }
    case 2: { println("two");   }
    default: { println("other"); }
}

// Works on strings
switch (color) {
    case "red":   { println("Stop");  }
    case "green": { println("Go");    }
    default:      { println("Slow");  }
}

// Works on enum values
switch (dir) {
    case Direction.NORTH: { println("Up");    }
    case Direction.SOUTH: { println("Down");  }
}

// Smart grade example — switch on true
switch (true) {
    case score >= 90: { grade = "A"; }
    case score >= 80: { grade = "B"; }
    default:          { grade = "C"; }
}
```

- **No fall-through** — only the first matching case executes
- Works with numbers, strings, booleans, and enum values
- `default` is optional

---

## 5. Constants

```
const int    MAX_SIZE = 1000;
const float  PI       = 3.14159;
const string APP_NAME = "MyApp";

// Reassignment causes a runtime error:
// MAX_SIZE = 999;  → [Runtime Error] Cannot reassign constant
```

---

## 6. Enums

```
enum Direction { NORTH, SOUTH, EAST, WEST }
enum Status    { ACTIVE, INACTIVE, PENDING }

Direction d = Direction.NORTH;
println(d);           // "Direction.NORTH"

// Compare enum values
if (d == Direction.NORTH) {
    println("Heading north");
}

// Use in switch
switch (d) {
    case Direction.NORTH: { println("Up");   }
    case Direction.SOUTH: { println("Down"); }
}
```

---

## 7. Import

```
// utils.zen
int double(int x) { return x * 2; }
string greet(string name) { return "Hello, " + name + "!"; }

// main.zen
import "utils.zen";
println(double(21));      // 42
println(greet("Ayush"));  // Hello, Ayush!
```

- Paths are relative to the importing file
- Each file is imported at most once (guards against circular imports)
- Imported functions, structs, and enums are all available after import

---

## 8. File I/O

```
// Write
file f = open("data.txt", "w");
writeLine(f, "First line");
write(f, "Second ");
write(f, "line\n");
close(f);

// Read line by line
file f2 = open("data.txt", "r");
string line = readLine(f2);
while (line != null) {
    println(line);
    line = readLine(f2);
}
close(f2);

// Read entire file
file f3 = open("data.txt", "r");
string all = readAll(f3);
close(f3);
println(all);

// Append
file f4 = open("data.txt", "a");
writeLine(f4, "Appended line");
close(f4);
```

| Function | Description |
|---|---|
| `open(path, mode)` | Open file. mode: `"r"`, `"w"`, `"a"` |
| `readLine(f)` | Read one line, returns `null` at EOF |
| `readAll(f)` | Read entire file as one string |
| `write(f, str)` | Write string (no newline) |
| `writeLine(f, str)` | Write string + newline |
| `close(f)` | Close and flush the file |
| `isEOF(f)` | Returns 1 if end of file reached |

---

## Full Built-in Reference

### I/O
`println(v)` `print(v)` `input(prompt)`

### Type Conversion
`toInt(v)` `toFloat(v)` `toString(v)` `typeOf(v)`

### Math
`sqrt` `pow` `abs` `floor` `ceil` `round` `max` `min` `random` `log` `log10` `sin` `cos` `tan`

### Array
`len(arr)` `append(arr,v)` `remove(arr,i)` `reverse(arr)` `sort(arr)`

### String (global)
`length(s)` `toUpper(s)` `toLower(s)` `trim(s)` `charAt(s,i)` `indexOf(s,sub)` `contains(s,sub)` `replace(s,old,new)` `startsWith(s,pfx)` `endsWith(s,sfx)` `substring(s,start,end)` `split(s,delim)`

### String (method syntax)
`s.length()` `s.toUpper()` `s.toLower()` `s.contains(sub)` `s.replace(old,new)` `s.split(delim)` `s.trim()` `s.charAt(i)` `s.indexOf(sub)` `s.startsWith(pfx)` `s.endsWith(sfx)` `s.substring(start,end)`

### Array (method syntax)
`arr.length()` `arr.append(v)` `arr.remove(i)` `arr.reverse()` `arr.contains(v)` `arr.indexOf(v)` `arr.join(sep)`

### Map (method syntax)
`m.size()` `m.keys()` `m.values()` `m.containsKey(k)` `m.remove(k)` `m.has(k)`

---

## 5 Suggested Future Features

1. **Exception Handling** — `try { } catch (string msg) { }` for graceful error recovery
2. **Generics / Typed Arrays** — `array<int>` and `array<Student>` for type-safe collections
3. **Lambda / Closures** — `int square = (int x) -> x * x;` for functional programming patterns
4. **Interfaces / Traits** — `interface Printable { void print(); }` for polymorphism
5. **String Interpolation** — `println($"Hello {name}, you are {age} years old");` for cleaner string formatting
