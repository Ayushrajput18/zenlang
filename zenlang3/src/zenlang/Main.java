package zenlang;

import zenlang.ast.AST.ASTNode;
import zenlang.interpreter.Interpreter;
import zenlang.lexer.Lexer;
import zenlang.lexer.Token;
import zenlang.parser.Parser;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static final String VERSION = "3.0.0";

    public static void main(String[] args) {
        if (args.length >= 1) {
            runFile(args[0]);
        } else {
            runRepl();
        }
    }

    // --- File execution mode ---
    private static void runFile(String path) {
        Path filePath = Paths.get(path).toAbsolutePath();
        String source;
        try { source = Files.readString(filePath); }
        catch (IOException e) {
            System.err.println("Could not open file: " + path);
            System.exit(1); return;
        }
        try {
            List<Token>   tokens = new Lexer(source).tokenize();
            List<ASTNode> ast    = new Parser(tokens).parse();
            Interpreter interp   = new Interpreter();
            interp.setBaseDir(filePath.getParent().toString());
            interp.interpret(ast);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    // --- Interactive REPL mode ---
    private static void runRepl() {
        System.out.println("+======================================+");
        System.out.println("|        Zen-Lang v" + VERSION + " REPL          |");
        System.out.println("|  Type :help for commands, :quit to  |");
        System.out.println("|  exit. Multi-line input supported.  |");
        System.out.println("+======================================+");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        Interpreter interp = new Interpreter();
        interp.setBaseDir(System.getProperty("user.dir"));

        StringBuilder buffer = new StringBuilder();
        int braceDepth = 0;
        boolean inMultiLine = false;

        while (true) {
            System.out.print(inMultiLine ? "...  " : "zen> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine();

            // Handle commands only on fresh input
            if (!inMultiLine && line.startsWith(":")) {
                String cmd = line.trim().toLowerCase();
                switch (cmd) {
                    case ":quit": case ":q": case ":exit":
                        System.out.println("Goodbye!");
                        return;
                    case ":help": case ":h":
                        printHelp();
                        continue;
                    case ":clear": case ":c":
                        interp = new Interpreter();
                        interp.setBaseDir(System.getProperty("user.dir"));
                        System.out.println("State cleared.");
                        continue;
                    default:
                        // :run <file>
                        if (cmd.startsWith(":run ")) {
                            runFile(line.substring(5).trim());
                            continue;
                        }
                        System.out.println("Unknown command: " + cmd + "  (type :help)");
                        continue;
                }
            }

            // Track brace depth for multi-line input
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }
            buffer.append(line).append("\n");

            if (braceDepth > 0) {
                inMultiLine = true;
                continue;
            }

            // Execute the buffer
            String code = buffer.toString().trim();
            buffer.setLength(0);
            braceDepth = 0;
            inMultiLine = false;

            if (code.isEmpty()) continue;

            try {
                List<Token>   tokens = new Lexer(code).tokenize();
                List<ASTNode> ast    = new Parser(tokens).parse();
                interp.interpret(ast);
            } catch (RuntimeException e) {
                System.err.println("[Error] " + e.getMessage());
            }
        }
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  :help  (:h)    Show this help");
        System.out.println("  :quit  (:q)    Exit the REPL");
        System.out.println("  :clear (:c)    Reset interpreter state");
        System.out.println("  :run <file>    Execute a .zen file");
        System.out.println();
        System.out.println("Tips:");
        System.out.println("  - Multi-line blocks (if, while, struct, etc.)");
        System.out.println("    are auto-detected via { } braces.");
        System.out.println("  - Variables persist across lines.");
    }
}
