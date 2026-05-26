package zenlang;

import zenlang.ast.AST.ASTNode;
import zenlang.interpreter.Interpreter;
import zenlang.lexer.Lexer;
import zenlang.lexer.Token;
import zenlang.parser.Parser;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java zenlang.Main <source.zen>");
            System.exit(1);
        }
        Path filePath = Paths.get(args[0]).toAbsolutePath();
        String source;
        try { source = Files.readString(filePath); }
        catch (IOException e) {
            System.err.println("Could not open file: " + args[0]);
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
}
