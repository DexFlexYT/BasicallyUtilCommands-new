package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.NbtPathArgument.NbtPath;
import net.minecraft.nbt.*;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("null")
public class EvalCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("basicallyutilcommands/eval");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("eval")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("expression", StringArgumentType.greedyString())
                        .executes(EvalCommand::execute)
                )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String expression = StringArgumentType.getString(context, "expression");
        
        try {
            double result = evaluateExpression(expression, context.getSource());
            context.getSource().sendSuccess(
                    () -> Component.literal("Result: " + result),
                    false
            );
            return (int) result;
        } catch (EvalException e) {
            LOGGER.error("Eval expression failed: {}", e.getDetailedMessage());
            context.getSource().sendFailure(Component.literal("Eval error: " + e.getMessage()));
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }
    }

    /**
     * Public API for evaluating expressions. Reused by ExecuteEvalCommand.
     * Source can be null for basic expressions (numbers, operators, functions)
     */
    public static double evaluateExpression(String expression, CommandSourceStack source) throws EvalException {
        try {
            ExpressionEvaluator evaluator = new ExpressionEvaluator(source, expression);
            return evaluator.evaluate(expression);
        } catch (EvalException e) {
            throw e;
        } catch (Exception e) {
            throw new EvalException("Unexpected error: " + e.getMessage(), expression, -1);
        }
    }

    /* ======================= CUSTOM EXCEPTION ======================= */

    public static class EvalException extends Exception {
        private final String expression;
        private final int position;

        public EvalException(String message, String expression, int position) {
            super(message);
            this.expression = expression;
            this.position = position;
        }

        public String getDetailedMessage() {
            if (position >= 0 && position < expression.length()) {
                return String.format("[pos %d] %s\n  Expression: %s\n  Position:   %s^", 
                    position, getMessage(), expression, " ".repeat(position));
            }
            return String.format("Expression: %s\nError: %s", expression, getMessage());
        }
    }

    /* ======================= EVALUATOR ======================= */

    private static class ExpressionEvaluator {
        private final CommandSourceStack source;
        @SuppressWarnings("unused")
        private final String expression;
        private final Tokenizer tokenizer = new Tokenizer();

        ExpressionEvaluator(CommandSourceStack source, String expression) {
            this.source = source;
            this.expression = expression;
        }

        double evaluate(String expr) throws EvalException {
            List<Token> tokens = tokenizer.tokenize(expr);
            Parser parser = new Parser(tokens, source, expr);
            return parser.parse();
        }
    }

    /* ======================= TOKENIZER ======================= */

    private static class Tokenizer {
        private static final Pattern TOKEN_PATTERN = Pattern.compile(
                "\\$[^\\$]+\\$[^\\$]+\\$|" +
                        "\\d*\\.\\d+|\\d+|" +
                        "&&|\\|\\||" +
                        "==|!=|<=|>=|[<>]=?|" +
                        "@[aeprs](?:\\[[^]]*])?|" +
                        "[a-zA-Z_#][a-zA-Z0-9_]*|" +
                        "[+\\-*/%^()\\[\\]]|" +
                        "!|\\?|,"
        );

        List<Token> tokenize(String input) {
            List<Token> tokens = new ArrayList<>();
            Matcher matcher = TOKEN_PATTERN.matcher(input);

            while (matcher.find()) {
                String v = matcher.group();
                tokens.add(new Token(typeOf(v), v));
            }
            return tokens;
        }

        private TokenType typeOf(String v) {
            if (v.startsWith("$")) return TokenType.STORAGE;
            if (v.matches("\\d*\\.\\d+|\\d+")) return TokenType.NUMBER;
            if (v.matches("==|!=|<=|>=|<|>")) return TokenType.COMPARISON;
            if (v.equals("&&")) return TokenType.AND;
            if (v.equals("||")) return TokenType.OR;
            switch (v) {
                case "!" -> { return TokenType.NOT; }
                case "(" -> { return TokenType.LPAREN; }
                case ")" -> { return TokenType.RPAREN; }
                case "[" -> { return TokenType.LBRACKET; }
                case "]" -> { return TokenType.RBRACKET; }
                case "?" -> { return TokenType.QUESTION; }
                case "," -> { return TokenType.COMMA; }
            }
            if (v.matches("[+\\-*/%^]")) return TokenType.OPERATOR;
            if (v.startsWith("@")) return TokenType.SELECTOR;
            return TokenType.IDENTIFIER;
        }
    }

    private enum TokenType {
        NUMBER, OPERATOR, COMPARISON, AND, OR, NOT,
        LPAREN, RPAREN, LBRACKET, RBRACKET,
        IDENTIFIER, SELECTOR, STORAGE,
        QUESTION, COMMA
    }

    private record Token(TokenType type, String value) {}

    /* ======================= PARSER ======================= */

    private static class Parser {
        private final List<Token> tokens;
        private final CommandSourceStack source;
        private final String expression;
        private int pos = 0;

        Parser(List<Token> tokens, CommandSourceStack source, String expression) {
            this.tokens = tokens;
            this.source = source;
            this.expression = expression;
        }

        double parse() throws EvalException {
            double v = or();
            if (pos != tokens.size()) {
                Token unexpected = tokens.get(pos);
                throw new EvalException(
                    "Unexpected token: '" + unexpected.value + "' (type: " + unexpected.type + ")",
                    expression, findTokenPosition(unexpected)
                );
            }
            return v;
        }

        private int findTokenPosition(Token target) {
            int pos = 0;
            for (int i = 0; i < this.pos && i < tokens.size(); i++) {
                Token t = tokens.get(i);
                pos = expression.indexOf(t.value, pos);
                if (pos < 0) return -1;
                pos += t.value.length();
            }
            return Math.max(0, pos - 1);
        }

        /* ---------- precedence ---------- */

        private double or() throws EvalException {
            double left = and();

            while (pos < tokens.size() && tokens.get(pos).type == TokenType.OR) {
                pos++;
                double right = and();
                left = (left != 0.0 || right != 0.0) ? 1.0 : 0.0;
            }

            return left;
        }

        private double and() throws EvalException {
            double left = comparison();

            while (pos < tokens.size() && tokens.get(pos).type == TokenType.AND) {
                pos++;
                double right = comparison();
                left = (left != 0.0 && right != 0.0) ? 1.0 : 0.0;
            }

            return left;
        }

        private double comparison() throws EvalException {
            double left = addSub();

            if (pos < tokens.size() && tokens.get(pos).type == TokenType.COMPARISON) {
                Token op = tokens.get(pos++);
                double right = addSub();

                return switch (op.value) {
                    case "==" -> left == right ? 1.0 : 0.0;
                    case "!=" -> left != right ? 1.0 : 0.0;
                    case "<"  -> left < right  ? 1.0 : 0.0;
                    case "<=" -> left <= right ? 1.0 : 0.0;
                    case ">"  -> left > right  ? 1.0 : 0.0;
                    case ">=" -> left >= right ? 1.0 : 0.0;
                    default -> throw new EvalException("Unknown comparison operator: " + op.value, expression, -1);
                };
            }

            return left;
        }

        private double addSub() throws EvalException {
            double v = mulDiv();
            while (pos < tokens.size()) {
                Token t = tokens.get(pos);
                if (t.type != TokenType.OPERATOR || !(t.value.equals("+") || t.value.equals("-"))) break;
                pos++;
                double r = mulDiv();
                v = t.value.equals("+") ? v + r : v - r;
            }
            return v;
        }

        private double mulDiv() throws EvalException {
            double v = power();
            while (pos < tokens.size()) {
                Token t = tokens.get(pos);
                if (t.type != TokenType.OPERATOR || !(t.value.equals("*") || t.value.equals("/") || t.value.equals("%"))) break;
                pos++;
                double r = power();
                switch (t.value) {
                    case "*" -> v *= r;
                    case "/" -> v /= r;
                    case "%" -> v %= r;
                }
            }
            return v;
        }

        private double power() throws EvalException {
            double v = unary();
            if (pos < tokens.size()) {
                Token t = tokens.get(pos);
                if (t.type == TokenType.OPERATOR && t.value.equals("^")) {
                    pos++;
                    v = Math.pow(v, power());
                }
            }
            return v;
        }

        private double unary() throws EvalException {
            if (pos < tokens.size()) {
                Token t = tokens.get(pos);
                if (t.type == TokenType.NOT) {
                    pos++;
                    double v = unary();
                    return v == 0.0 ? 1.0 : 0.0;
                }
                if (t.type == TokenType.OPERATOR && (t.value.equals("+") || t.value.equals("-"))) {
                    pos++;
                    double v = unary();
                    return t.value.equals("-") ? -v : v;
                }
            }
            return primary();
        }

        /* ---------- atoms ---------- */

        private double primary() throws EvalException {
            if (pos >= tokens.size())
                throw new EvalException("Unexpected end of expression, expected value", expression, expression.length());

            Token t = tokens.get(pos);

            if (t.type == TokenType.LPAREN) {
                pos++;
                double v = or();
                expect(TokenType.RPAREN);
                return v;
            }

            if (t.type == TokenType.LBRACKET) {
                pos++;
                double v = or();
                expect(TokenType.RBRACKET);
                return v;
            }

            if (t.type == TokenType.NUMBER) {
                pos++;
                return Double.parseDouble(t.value);
            }

            if (t.type == TokenType.STORAGE) {
                pos++;
                return storageValue(t.value);
            }

            if (t.type == TokenType.IDENTIFIER) {
                String name = t.value.toLowerCase();

                // Position context variables
                if (name.equals("px")) { 
                    pos++; 
                    if (source == null) throw new EvalException("Position context (px) not available without command source", expression, findTokenPosition(t));
                    return source.getPosition().x; 
                }
                if (name.equals("py")) { 
                    pos++; 
                    if (source == null) throw new EvalException("Position context (py) not available without command source", expression, findTokenPosition(t));
                    return source.getPosition().y; 
                }
                if (name.equals("pz")) { 
                    pos++; 
                    if (source == null) throw new EvalException("Position context (pz) not available without command source", expression, findTokenPosition(t));
                    return source.getPosition().z; 
                }

                // Rotation context variables
                if (name.equals("rx")) { 
                    pos++; 
                    if (source == null) throw new EvalException("Rotation context (rx) not available without command source", expression, findTokenPosition(t));
                    return source.getRotation().x; 
                }
                if (name.equals("ry")) { 
                    pos++; 
                    if (source == null) throw new EvalException("Rotation context (ry) not available without command source", expression, findTokenPosition(t));
                    return source.getRotation().y; 
                }

                // Constants
                if (name.equals("pi"))   { pos++; return Math.PI; }
                if (name.equals("tau"))  { pos++; return Math.PI * 2.0; }
                if (name.equals("e"))    { pos++; return Math.E; }
                if (name.equals("rad"))    { pos++; return Math.PI / 180.0; }
                if (name.equals("deg"))    { pos++; return 180.0 / Math.PI; }

                if (peek(TokenType.LPAREN, 1)) {
                    pos += 2;
                    List<Double> args = functionArgs();
                    return evalFunction(name, args);
                }
            }

            if (isBareScoreRef()) {
                List<Double> v = scoreRefList();
                if (v.isEmpty()) return 0.0;
                return v.stream().mapToDouble(d -> d).average().orElse(0.0);
            }

            throw new EvalException(
                "Cannot parse token: '" + t.value + "' (type: " + t.type + ")",
                expression, findTokenPosition(t)
            );
        }

        /* ---------- functions ---------- */

        private List<Double> functionArgs() throws EvalException {
            List<Double> out = new ArrayList<>();
            if (peek(TokenType.RPAREN, 0)) {
                pos++;
                return out;
            }
            out.addAll(argument());
            while (peek(TokenType.COMMA, 0)) {
                pos++;
                out.addAll(argument());
            }
            expect(TokenType.RPAREN);
            return out;
        }

        private List<Double> argument() throws EvalException {
            if (isBareScoreRef()) {
                int after = pos + 3;
                if (after >= tokens.size()
                        || tokens.get(after).type == TokenType.COMMA
                        || tokens.get(after).type == TokenType.RPAREN) {
                    return scoreRefList();
                }
            }
            return List.of(comparison());
        }

        /* ---------- scoreboard ---------- */

        private boolean isBareScoreRef() {
            if (pos + 2 >= tokens.size()) return false;
            Token a = tokens.get(pos);
            Token b = tokens.get(pos + 1);
            Token c = tokens.get(pos + 2);
            return (a.type == TokenType.IDENTIFIER || a.type == TokenType.SELECTOR)
                    && b.type == TokenType.QUESTION
                    && c.type == TokenType.IDENTIFIER;
        }

        private List<Double> scoreRefList() throws EvalException {
            Token t = tokens.get(pos);
            return scoreRefList(t.value);
        }

        private List<Double> scoreRefList(String target) throws EvalException {
            pos++;
            expect(TokenType.QUESTION);
            Token obj = expect(TokenType.IDENTIFIER);
            return getScores(target, obj.value);
        }

        private List<Double> getScores(String target, String objective) throws EvalException {
            if (source == null) throw new EvalException("Scoreboard access requires command source", expression, expression.indexOf(objective));
            
            Scoreboard board = source.getServer().getScoreboard();
            Objective obj = board.getObjective(objective);
            if (obj == null)
                throw new EvalException(
                    "Scoreboard objective does not exist: '" + objective + "'",
                    expression, expression.indexOf(objective)
                );

            List<String> holders = resolveTargets(target);
            List<Double> out = new ArrayList<>();

            for (String h : holders) {
                ScoreHolder holder = ScoreHolder.forNameOnly(h);
                ScoreAccess s = board.getOrCreatePlayerScore(holder, obj);
                out.add((double) s.get());
            }
            return out;
        }

        private List<String> resolveTargets(String target) throws EvalException {
            if (!target.startsWith("@")) return List.of(target);

            if (source == null) throw new EvalException("Entity selectors require command source", expression, expression.indexOf(target));

            try {
                Collection<? extends net.minecraft.world.entity.Entity> entities =
                        new EntitySelectorParser(new StringReader(target), true)
                                .parse()
                                .findEntities(source);

                List<String> out = new ArrayList<>();
                for (var e : entities) out.add(e.getScoreboardName());
                return out;
            } catch (Exception e) {
                throw new EvalException(
                    "Invalid entity selector: '" + target + "' (" + e.getMessage() + ")",
                    expression, expression.indexOf(target)
                );
            }
        }

        private double storageValue(String raw) throws EvalException {
            if (source == null) {
                throw new EvalException("Storage references require command source", expression, findTokenPosition(tokens.get(pos - 1)));
            }

            String[] parts = raw.split("\\$", -1);
            if (parts.length != 4 || parts[0].length() != 0 || parts[3].length() != 0) {
                throw new EvalException("Invalid storage reference syntax", expression, findTokenPosition(tokens.get(pos - 1)));
            }

            String storageId = parts[1];
            String path = parts[2];
            Identifier identifier;
            try {
                identifier = Identifier.parse(storageId);
            } catch (Exception e) {
                throw new EvalException("Invalid storage identifier: " + storageId, expression, findTokenPosition(tokens.get(pos - 1)));
            }

            CompoundTag storage = source.getServer().getCommandStorage().get(identifier);
            // if (storage == null) return 0.0;

            NbtPath nbtPath;
            try {
                nbtPath = NbtPathArgument.nbtPath().parse(new StringReader(path));
            } catch (CommandSyntaxException e) {
                throw new EvalException("Invalid NBT path: " + e.getMessage(), expression, findTokenPosition(tokens.get(pos - 1)));
            }

            List<? extends Tag> values;
                try {
                    values = nbtPath.get(storage);
                } catch (CommandSyntaxException e) {
                    throw new EvalException("Invalid NBT path: " + e.getMessage(), expression, findTokenPosition(tokens.get(pos - 1)));
                }
                if (values == null || values.isEmpty()) return 0.0;

            return values.stream()
                    .mapToDouble(this::nbtValue)
                    .average()
                    .orElse(0.0);
        }

        private double nbtValue(Tag element) {
        if (element instanceof NumericTag number) {
            return number.doubleValue();
        }
        if (element instanceof StringTag string) {
            String text = string.value();
            if (text.equalsIgnoreCase("true")) return 1.0;
            if (text.equalsIgnoreCase("false")) return 0.0;
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return text.length();
            }
        }
        if (element instanceof ListTag list) {
            return list.size();
        }
        if (element instanceof CompoundTag compound) {
            return compound.keySet().size();
        }
        if (element instanceof ByteArrayTag byteArray) {
            return byteArray.getAsByteArray().length;
        }
        if (element instanceof IntArrayTag intArray) {
            return intArray.getAsIntArray().length;
        }
        if (element instanceof LongArrayTag longArray) {
            return longArray.getAsLongArray().length;
        }
        return 0.0;
    }

        /* ---------- helpers ---------- */

        private boolean peek(TokenType t, int off) {
            int i = pos + off;
            return i < tokens.size() && tokens.get(i).type == t;
        }

        private Token expect(TokenType t) throws EvalException {
            if (pos >= tokens.size() || tokens.get(pos).type != t) {
                String expected = t.toString().toLowerCase();
                String got = pos < tokens.size() 
                    ? tokens.get(pos).type.toString().toLowerCase() + " ('" + tokens.get(pos).value + "')"
                    : "end of expression";
                throw new EvalException(
                    "Expected " + expected + ", got " + got,
                    expression, pos < tokens.size() ? findTokenPosition(tokens.get(pos)) : expression.length()
                );
            }
            return tokens.get(pos++);
        }

        private double evalFunction(String name, List<Double> a) throws EvalException {
            return switch (name) {
                case "min" -> a.stream().mapToDouble(d -> d).min().orElseThrow();
                case "max" -> a.stream().mapToDouble(d -> d).max().orElseThrow();
                case "sum" -> a.stream().mapToDouble(d -> d).sum();
                case "avg" -> a.stream().mapToDouble(d -> d).average().orElse(0);
                case "len" -> a.size();
                case "clamp" -> {
                    if (a.size() != 3) throw new EvalException("clamp() expects 3 arguments, got " + a.size(), expression, -1);
                    yield Math.max(a.get(1), Math.min(a.get(2), a.get(0)));
                }

                case "abs" -> Math.abs(one(a));
                case "floor" -> Math.floor(one(a));
                case "ceil" -> Math.ceil(one(a));
                case "round" -> Math.round(one(a));
                case "sqrt" -> Math.sqrt(one(a));
                case "pow" -> {
                    if (a.size() != 2) throw new EvalException("pow() expects 2 arguments, got " + a.size(), expression, -1);
                    yield Math.pow(a.get(0), a.get(1));
                }

                case "sin" -> Math.sin(one(a));
                case "cos" -> Math.cos(one(a));
                case "tan" -> Math.tan(one(a));

                case "lerp" -> {
                    if (a.size() != 3) throw new EvalException("lerp() expects 3 arguments, got " + a.size(), expression, -1);
                    yield a.get(0) + (a.get(1) - a.get(0)) * a.get(2);
                }

                case "smoothstep" -> {
                    if (a.size() != 3) throw new EvalException("smoothstep() expects 3 arguments, got " + a.size(), expression, -1);
                    double x = clamp01((a.get(2) - a.get(0)) / (a.get(1) - a.get(0)));
                    yield x * x * (3 - 2 * x);
                }

                case "map" -> {
                    if (a.size() != 5) throw new EvalException("map() expects 5 arguments, got " + a.size(), expression, -1);
                    double t = (a.get(0) - a.get(1)) / (a.get(2) - a.get(1));
                    yield a.get(3) + t * (a.get(4) - a.get(3));
                }

                case "log" -> {
                    if (a.size() != 2) throw new EvalException("log() expects 2 arguments, got " + a.size(), expression, -1);
                    yield Math.log(a.get(0)) / Math.log(a.get(1));
                }
                case "ln" -> Math.log(one(a));

                case "rand" -> {
                    if (a.size() == 2)
                        yield a.get(0) + Math.random() * (a.get(1) - a.get(0));
                    if (a.size() == 3) {
                        Random r = new Random(a.get(2).longValue());
                        yield a.get(0) + r.nextDouble() * (a.get(1) - a.get(0));
                    }
                    throw new EvalException("rand() expects 2 or 3 arguments, got " + a.size(), expression, -1);
                }

                case "dot" -> {
                    if (a.size() != 6) throw new EvalException("dot() expects 6 arguments, got " + a.size(), expression, -1);
                    yield a.get(0) * a.get(3) + a.get(1) * a.get(4) + a.get(2) * a.get(5);
                }

                case "euler_dot" -> {
                    if (a.size() != 4) throw new EvalException("euler_dot() expects 4 arguments, got " + a.size(), expression, -1);
                    double pitch1 = Math.toRadians(a.get(0));
                    double yaw1 = Math.toRadians(a.get(1));
                    double pitch2 = Math.toRadians(a.get(2));
                    double yaw2 = Math.toRadians(a.get(3));

                    double x1 = -Math.sin(yaw1) * Math.cos(pitch1);
                    double y1 = -Math.sin(pitch1);
                    double z1 = Math.cos(yaw1) * Math.cos(pitch1);

                    double x2 = -Math.sin(yaw2) * Math.cos(pitch2);
                    double y2 = -Math.sin(pitch2);
                    double z2 = Math.cos(yaw2) * Math.cos(pitch2);

                    yield x1 * x2 + y1 * y2 + z1 * z2;
                }

                default -> throw new EvalException("Unknown function: '" + name + "'", expression, expression.indexOf(name));
            };
        }

        private double one(List<Double> a) throws EvalException {
            if (a.size() != 1)
                throw new EvalException("Expected 1 argument, got " + a.size(), expression, -1);
            return a.getFirst();
        }

        private double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }
}