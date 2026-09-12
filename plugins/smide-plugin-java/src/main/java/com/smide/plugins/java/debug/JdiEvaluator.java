package com.smide.plugins.java.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out what a Java expression means in a suspended frame.
 *
 * <p>Not a Java compiler, and not trying to be. What people type into a debugger is
 * overwhelmingly a name, something reached from a name, or a question about one:
 * {@code stage}, {@code this.scale.getX()}, {@code args[0]}, {@code list.size() > 0}.
 * So this reads names, fields, array elements, method calls, literals and the arithmetic
 * and comparison operators, and says plainly what it cannot do instead of half-doing it.
 * Casts, assignment, {@code new}, lambdas and generics are out.
 *
 * <p>Two details about JDI shape the code. Calling a method in the debuggee resumes the
 * thread for the duration of the call, which invalidates every {@link StackFrame} fetched
 * before it - so the frame is fetched afresh each time rather than held. And the call is
 * made {@linkplain ObjectReference#INVOKE_SINGLE_THREADED single-threaded}, so a getter
 * cannot let the rest of the program run on while the debugger waits; the cost is that a
 * method needing a lock another thread holds will hang instead, which is the bargain
 * every debugger makes here.
 */
final class JdiEvaluator {

    /** An expression that cannot be worked out, with the reason a reader can act on. */
    static final class EvalException extends Exception {
        EvalException(String message) {
            super(message);
        }
    }

    private final ThreadReference thread;
    private final int frameIndex;
    private final VirtualMachine vm;
    private final List<Token> tokens;
    private int at;

    private JdiEvaluator(ThreadReference thread, int frameIndex, List<Token> tokens) {
        this.thread = thread;
        this.frameIndex = frameIndex;
        this.vm = thread.virtualMachine();
        this.tokens = tokens;
    }

    /**
     * @param thread     a thread the VM has suspended
     * @param frameIndex the frame the expression is read in, 0 being the innermost
     * @return the value, which is null when the expression is Java's null
     */
    static Value evaluate(ThreadReference thread, int frameIndex, String expression)
            throws EvalException {
        String text = expression == null ? "" : expression.strip();
        if (text.isEmpty()) {
            throw new EvalException("Nothing to evaluate.");
        }
        JdiEvaluator evaluator = new JdiEvaluator(thread, frameIndex, Token.scan(text));
        Ref result = evaluator.expression();
        evaluator.expect(Token.END, "");
        return evaluator.value(result);
    }

    // ------------------------------------------------------------------ grammar

    private Ref expression() throws EvalException {
        return equality();
    }

    private Ref equality() throws EvalException {
        Ref left = relational();
        while (peekOp("==") || peekOp("!=")) {
            String op = next().text();
            left = Ref.of(Operators.binary(vm, this, op, value(left), value(relational())));
        }
        return left;
    }

    private Ref relational() throws EvalException {
        Ref left = additive();
        while (peekOp("<") || peekOp("<=") || peekOp(">") || peekOp(">=")) {
            String op = next().text();
            left = Ref.of(Operators.binary(vm, this, op, value(left), value(additive())));
        }
        return left;
    }

    private Ref additive() throws EvalException {
        Ref left = multiplicative();
        while (peekOp("+") || peekOp("-")) {
            String op = next().text();
            left = Ref.of(Operators.binary(vm, this, op, value(left), value(multiplicative())));
        }
        return left;
    }

    private Ref multiplicative() throws EvalException {
        Ref left = unary();
        while (peekOp("*") || peekOp("/") || peekOp("%")) {
            String op = next().text();
            left = Ref.of(Operators.binary(vm, this, op, value(left), value(unary())));
        }
        return left;
    }

    private Ref unary() throws EvalException {
        if (peekOp("-")) {
            next();
            return Ref.of(Operators.negate(vm, value(unary())));
        }
        if (peekOp("!")) {
            next();
            return Ref.of(Operators.not(vm, value(unary())));
        }
        return postfix();
    }

    private Ref postfix() throws EvalException {
        Ref ref = primary();
        while (true) {
            if (peekOp(".")) {
                next();
                String name = expect(Token.IDENT, "a field or method name").text();
                ref = peekOp("(") ? Ref.of(call(ref, name, arguments())) : member(ref, name);
            } else if (peekOp("[")) {
                next();
                Value index = value(expression());
                expect("]");
                ref = Ref.of(element(value(ref), index));
            } else {
                return ref;
            }
        }
    }

    private List<Value> arguments() throws EvalException {
        expect("(");
        List<Value> args = new ArrayList<>();
        if (!peekOp(")")) {
            args.add(value(expression()));
            while (peekOp(",")) {
                next();
                args.add(value(expression()));
            }
        }
        expect(")");
        return args;
    }

    private Ref primary() throws EvalException {
        Token token = next();
        if (token.is(Token.END)) {
            throw new EvalException("The expression ends too soon.");
        }
        if (token.is(Token.NUMBER)) {
            return Ref.of(Literals.number(vm, token.text()));
        }
        if (token.is(Token.STRING)) {
            return Ref.of(vm.mirrorOf(Literals.unquote(token.text())));
        }
        if (token.is(Token.CHAR)) {
            return Ref.of(vm.mirrorOf(Literals.character(token.text())));
        }
        if (token.is(Token.OP) && token.text().equals("(")) {
            Ref inner = expression();
            expect(")");
            return inner;
        }
        if (!token.is(Token.IDENT)) {
            throw new EvalException("Did not expect '" + token.text() + "' here.");
        }
        return switch (token.text()) {
            case "true" -> Ref.of(vm.mirrorOf(true));
            case "false" -> Ref.of(vm.mirrorOf(false));
            case "null" -> Ref.of(null);
            case "this" -> Ref.of(self());
            // A name with a bracket after it is a call on the frame itself: size(), getX().
            default -> peekOp("(") ? Ref.of(here(token.text(), arguments())) : name(token.text());
        };
    }

    /** An unqualified call: a method of this object if there is one, otherwise a static. */
    private Value here(String name, List<Value> args) throws EvalException {
        StackFrame frame = frame();
        ObjectReference self = frame.thisObject();
        if (self != null && !self.referenceType().methodsByName(name).isEmpty()) {
            return call(Ref.of(self), name, args);
        }
        return call(Ref.type(frame.location().declaringType()), name, args);
    }

    // ---------------------------------------------------------------- resolving

    /**
     * What a bare name means here.
     *
     * <p>In the order Java itself would look: a local, then a field of the object the
     * frame belongs to, then a static field of the class it is in. A name that is none of
     * those is not yet an error - it may be the start of {@code java.nio.file.Files} - so
     * it is carried along until a dot either completes a class name or runs out.
     */
    private Ref name(String identifier) throws EvalException {
        StackFrame frame = frame();
        try {
            LocalVariable local = frame.visibleVariableByName(identifier);
            if (local != null) {
                return Ref.of(frame.getValue(local));
            }
        } catch (com.sun.jdi.AbsentInformationException e) {
            // Compiled without -g: locals are invisible, but fields still work.
        }
        ObjectReference self = frame.thisObject();
        if (self != null) {
            Field field = self.referenceType().fieldByName(identifier);
            if (field != null) {
                return Ref.of(self.getValue(field));
            }
        }
        ReferenceType declaring = frame.location().declaringType();
        Field statik = declaring.fieldByName(identifier);
        if (statik != null && statik.isStatic()) {
            return Ref.of(declaring.getValue(statik));
        }
        ReferenceType type = classNamed(identifier);
        return type != null ? Ref.type(type) : Ref.name(identifier);
    }

    /** A dot after something: a field of a value, a static field, or more of a class name. */
    private Ref member(Ref target, String name) throws EvalException {
        if (target.isName()) {
            String dotted = target.pending() + "." + name;
            ReferenceType type = classNamed(dotted);
            return type != null ? Ref.type(type) : Ref.name(dotted);
        }
        if (target.isType()) {
            Field field = target.type().fieldByName(name);
            if (field == null || !field.isStatic()) {
                throw new EvalException(target.type().name() + " has no static field '" + name + "'.");
            }
            return Ref.of(target.type().getValue(field));
        }
        Value value = target.value();
        if (value == null) {
            throw new EvalException("Cannot read '" + name + "' of null.");
        }
        if (value instanceof ArrayReference array && name.equals("length")) {
            return Ref.of(vm.mirrorOf(array.length()));
        }
        if (!(value instanceof ObjectReference object)) {
            throw new EvalException(value.type().name() + " is not an object, so it has no '"
                    + name + "'.");
        }
        Field field = object.referenceType().fieldByName(name);
        if (field != null) {
            return Ref.of(object.getValue(field));
        }
        String hint = object.referenceType().methodsByName(name).isEmpty()
                ? "" : " Did you mean " + name + "()?";
        throw new EvalException(object.referenceType().name() + " has no field '" + name + "'." + hint);
    }

    private Value element(Value array, Value index) throws EvalException {
        if (array == null) {
            throw new EvalException("Cannot index null.");
        }
        if (!(array instanceof ArrayReference reference)) {
            throw new EvalException(array.type().name() + " is not an array.");
        }
        if (!(index instanceof PrimitiveValue number) || index instanceof BooleanValue) {
            throw new EvalException("An array index must be a number.");
        }
        int i = number.intValue();
        if (i < 0 || i >= reference.length()) {
            throw new EvalException("Index " + i + " is outside the array, which has "
                    + reference.length() + " elements.");
        }
        return reference.getValue(i);
    }

    /** Calls a method in the debuggee, which runs its code for real - side effects and all. */
    private Value call(Ref target, String name, List<Value> args) throws EvalException {
        if (target.isName()) {
            throw new EvalException("Cannot resolve '" + target.pending() + "'.");
        }
        ReferenceType owner = target.isType() ? target.type()
                : target.value() instanceof ObjectReference object ? object.referenceType() : null;
        if (owner == null) {
            throw new EvalException(target.value() == null
                    ? "Cannot call " + name + "() on null."
                    : "Cannot call " + name + "() on a " + target.value().type().name() + ".");
        }
        Method method = pick(owner, name, args);
        try {
            if (target.isType()) {
                if (!(owner instanceof ClassType classType)) {
                    throw new EvalException(owner.name() + " is not a class.");
                }
                if (!method.isStatic()) {
                    throw new EvalException(name + "() is not static; call it on an object.");
                }
                return classType.invokeMethod(thread, method, args, ClassType.INVOKE_SINGLE_THREADED);
            }
            ObjectReference object = (ObjectReference) target.value();
            return object.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (com.sun.jdi.InvocationException e) {
            throw new EvalException(name + "() threw " + e.exception().referenceType().name() + ".");
        } catch (IncompatibleThreadStateException e) {
            throw new EvalException("The thread is not suspended in a way that allows calls.");
        } catch (com.sun.jdi.InvalidTypeException | com.sun.jdi.ClassNotLoadedException e) {
            throw new EvalException("The arguments do not fit " + name + "(): " + e.getMessage());
        } catch (RuntimeException e) {
            throw new EvalException("Could not call " + name + "(): " + e);
        }
    }

    /**
     * Which overload a call means.
     *
     * <p>Arity alone is not enough - {@code Math.max} has four methods taking two numbers -
     * so each candidate is scored by how far the arguments are from its parameters, the
     * way javac chooses: an exact type beats a widening, a near supertype beats a distant
     * one, and only a genuine tie is reported as one.
     */
    private static Method pick(ReferenceType owner, String name, List<Value> args)
            throws EvalException {
        List<Method> byName = owner.methodsByName(name);
        if (byName.isEmpty()) {
            throw new EvalException(owner.name() + " has no method '" + name + "'.");
        }
        List<Method> arity = new ArrayList<>();
        for (Method method : byName) {
            if (method.argumentTypeNames().size() == args.size() && !method.isAbstract()) {
                arity.add(method);
            }
        }
        if (arity.isEmpty()) {
            throw new EvalException(name + "() takes " + arities(byName)
                    + " arguments, not " + args.size() + ".");
        }
        List<Method> best = new ArrayList<>();
        int bestCost = Integer.MAX_VALUE;
        for (Method method : arity) {
            int cost = cost(method.argumentTypeNames(), args);
            if (cost < 0 || cost > bestCost) {
                continue;
            }
            if (cost < bestCost) {
                best.clear();
                bestCost = cost;
            }
            best.add(method);
        }
        if (best.isEmpty()) {
            throw new EvalException("No " + name + "() here takes those argument types."
                    + " Values are passed as they are; nothing is boxed or cast.");
        }
        if (best.size() > 1) {
            throw new EvalException(owner.name() + " has " + best.size() + " methods called "
                    + name + " that fit these arguments equally well;"
                    + " this cannot tell which you mean.");
        }
        return best.get(0);
    }

    private static String arities(List<Method> methods) {
        List<String> counts = new ArrayList<>();
        for (Method method : methods) {
            String count = String.valueOf(method.argumentTypeNames().size());
            if (!counts.contains(count)) {
                counts.add(count);
            }
        }
        return String.join(" or ", counts);
    }

    /** The total distance from these arguments to those parameters, or -1 for no fit. */
    private static int cost(List<String> parameters, List<Value> args) {
        int total = 0;
        for (int i = 0; i < parameters.size(); i++) {
            int step = cost(parameters.get(i), args.get(i));
            if (step < 0) {
                return -1;
            }
            total += step;
        }
        return total;
    }

    private static int cost(String parameter, Value value) {
        boolean primitive = WIDENING.containsKey(parameter) || parameter.equals("boolean");
        if (value == null) {
            // null goes anywhere that is not a primitive, and is a poor fit for everything.
            return primitive ? -1 : 3;
        }
        String actual = value.type().name();
        if (actual.equals(parameter)) {
            return 0;
        }
        if (value instanceof PrimitiveValue) {
            Integer from = WIDENING.get(actual);
            Integer to = WIDENING.get(parameter);
            // Only widening, and char sits beside short without either reaching the other.
            if (from == null || to == null || to <= from) {
                return -1;
            }
            return to - from;
        }
        if (primitive) {
            return -1;
        }
        Integer distance = value instanceof ObjectReference object
                ? distance(object.referenceType(), parameter) : null;
        return distance == null ? -1 : distance;
    }

    /** byte to double, in the order Java widens them; boolean is deliberately absent. */
    private static final java.util.Map<String, Integer> WIDENING = java.util.Map.of(
            "byte", 1, "short", 2, "char", 2, "int", 3, "long", 4, "float", 5, "double", 6);

    /** How far a type is from one of its supertypes by name, or null when it is not one. */
    private static Integer distance(ReferenceType type, String target) {
        if (type.name().equals(target)) {
            return 0;
        }
        if (type instanceof ClassType classType) {
            int up = 1;
            for (ClassType parent = classType.superclass(); parent != null;
                    parent = parent.superclass(), up++) {
                if (parent.name().equals(target)) {
                    return up;
                }
            }
            for (com.sun.jdi.InterfaceType face : classType.allInterfaces()) {
                if (face.name().equals(target)) {
                    return 1;
                }
            }
        }
        return type instanceof com.sun.jdi.ArrayType && target.equals("java.lang.Object") ? 5 : null;
    }

    /**
     * The loaded class of that name, if any.
     *
     * <p>Only loaded classes can be found - the VM has no index of everything on its class
     * path - which is rarely a limit in practice, because a class worth asking about at a
     * breakpoint has usually run by then. A bare name is tried in java.lang and in the
     * package of the frame, the way source in that file would read it.
     */
    private ReferenceType classNamed(String name) {
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        if (!name.contains(".")) {
            candidates.add("java.lang." + name);
            String here = frameTypeName();
            int dot = here == null ? -1 : here.lastIndexOf('.');
            if (dot > 0) {
                candidates.add(here.substring(0, dot + 1) + name);
            }
        } else {
            // Outer.Inner is Outer$Inner to the VM.
            int dot = name.lastIndexOf('.');
            candidates.add(name.substring(0, dot) + "$" + name.substring(dot + 1));
        }
        for (String candidate : candidates) {
            List<ReferenceType> found = vm.classesByName(candidate);
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }

    private String frameTypeName() {
        try {
            return frame().location().declaringType().name();
        } catch (EvalException | RuntimeException e) {
            return null;
        }
    }

    private ObjectReference self() throws EvalException {
        ObjectReference self = frame().thisObject();
        if (self == null) {
            throw new EvalException("There is no 'this' here - the frame is a static method.");
        }
        return self;
    }

    /**
     * The frame, fetched every time.
     *
     * <p>A StackFrame is only valid while the thread stays suspended, and calling a method
     * in the debuggee resumes it briefly. Holding one across a call gives
     * InvalidStackFrameException on the next name read.
     */
    private StackFrame frame() throws EvalException {
        try {
            return thread.frame(frameIndex);
        } catch (IncompatibleThreadStateException e) {
            throw new EvalException("The program is running; stop it first.");
        } catch (IndexOutOfBoundsException e) {
            throw new EvalException("That frame is no longer on the stack.");
        }
    }

    private Value value(Ref ref) throws EvalException {
        if (ref.isName()) {
            throw new EvalException("Cannot resolve '" + ref.pending() + "'.");
        }
        if (ref.isType()) {
            throw new EvalException(ref.type().name() + " is a type, not a value.");
        }
        return ref.value();
    }

    /** A value as a string, the way {@code "" + x} would read it - toString() included. */
    private String text(Value value) throws EvalException {
        if (value == null) {
            return "null";
        }
        if (value instanceof StringReference string) {
            return string.value();
        }
        if (value instanceof PrimitiveValue primitive) {
            return primitive.toString();
        }
        Value string = call(Ref.of(value), "toString", List.of());
        return string instanceof StringReference result ? result.value() : "null";
    }

    // ------------------------------------------------------------------- tokens

    private Token next() {
        return tokens.get(Math.min(at++, tokens.size() - 1));
    }

    private Token peek() {
        return tokens.get(Math.min(at, tokens.size() - 1));
    }

    private boolean peekOp(String text) {
        return peek().is(Token.OP) && peek().text().equals(text);
    }

    private void expect(String op) throws EvalException {
        if (!peekOp(op)) {
            throw new EvalException("Expected '" + op + "' but found " + describe(peek()) + ".");
        }
        next();
    }

    private Token expect(int kind, String what) throws EvalException {
        if (!peek().is(kind)) {
            throw new EvalException(kind == Token.END
                    ? "Unexpected " + describe(peek()) + " after the expression."
                    : "Expected " + what + " but found " + describe(peek()) + ".");
        }
        return next();
    }

    private static String describe(Token token) {
        return token.is(Token.END) ? "the end of the expression" : "'" + token.text() + "'";
    }

    /** A token: just enough of one to read the shapes this understands. */
    record Token(int kind, String text) {

        static final int IDENT = 0;
        static final int NUMBER = 1;
        static final int STRING = 2;
        static final int CHAR = 3;
        static final int OP = 4;
        static final int END = 5;

        boolean is(int kind) {
            return this.kind == kind;
        }

        static List<Token> scan(String text) throws EvalException {
            List<Token> out = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = i;
                    while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                    out.add(new Token(IDENT, text.substring(start, i)));
                } else if (Character.isDigit(c)) {
                    int start = i;
                    while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i))
                            || text.charAt(i) == '.' || text.charAt(i) == '_')) {
                        i++;
                    }
                    out.add(new Token(NUMBER, text.substring(start, i)));
                } else if (c == '"' || c == '\'') {
                    int end = closing(text, i, c);
                    out.add(new Token(c == '"' ? STRING : CHAR, text.substring(i, end + 1)));
                    i = end + 1;
                } else {
                    String two = i + 1 < text.length() ? text.substring(i, i + 2) : "";
                    if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
                        out.add(new Token(OP, two));
                        i += 2;
                    } else if (".[](),+-*/%<>!".indexOf(c) >= 0) {
                        out.add(new Token(OP, String.valueOf(c)));
                        i++;
                    } else if (c == '=') {
                        throw new EvalException("This reads expressions; it does not assign.");
                    } else if (c == '&' || c == '|') {
                        throw new EvalException(c + " is not supported; ask the two halves"
                                + " as separate expressions.");
                    } else {
                        throw new EvalException("Cannot make sense of '" + c + "'.");
                    }
                }
            }
            out.add(new Token(END, ""));
            return out;
        }

        private static int closing(String text, int from, char quote) throws EvalException {
            for (int i = from + 1; i < text.length(); i++) {
                if (text.charAt(i) == '\\') {
                    i++;
                } else if (text.charAt(i) == quote) {
                    return i;
                }
            }
            throw new EvalException("The " + (quote == '"' ? "string" : "character")
                    + " starting at position " + (from + 1) + " is never closed.");
        }
    }

    /**
     * What a piece of an expression came to so far.
     *
     * <p>Three things, because a name is not always a value yet: {@code java} in
     * {@code java.util.List} is neither a variable nor a class until two more dots have
     * been read.
     */
    private record Ref(int kind, Value value, ReferenceType type, String pending) {

        private static final int VALUE = 0;
        private static final int TYPE = 1;
        private static final int NAME = 2;

        static Ref of(Value value) {
            return new Ref(VALUE, value, null, null);
        }

        static Ref type(ReferenceType type) {
            return new Ref(TYPE, null, type, null);
        }

        static Ref name(String pending) {
            return new Ref(NAME, null, null, pending);
        }

        boolean isType() {
            return kind == TYPE;
        }

        boolean isName() {
            return kind == NAME;
        }
    }

    /** Reading literals the way javac would, minus the parts nobody types at a breakpoint. */
    private static final class Literals {

        static Value number(VirtualMachine vm, String text) throws EvalException {
            String clean = text.replace("_", "");
            try {
                char last = clean.charAt(clean.length() - 1);
                if (last == 'L' || last == 'l') {
                    return vm.mirrorOf(Long.decode(clean.substring(0, clean.length() - 1)));
                }
                if (last == 'd' || last == 'D' || last == 'f' || last == 'F') {
                    return vm.mirrorOf(Double.parseDouble(clean.substring(0, clean.length() - 1)));
                }
                if (clean.contains(".") || clean.contains("e") || clean.contains("E")) {
                    return vm.mirrorOf(Double.parseDouble(clean));
                }
                return vm.mirrorOf(Integer.decode(clean));
            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                throw new EvalException("'" + text + "' is not a number this understands.");
            }
        }

        static String unquote(String quoted) {
            return escapes(quoted.substring(1, quoted.length() - 1));
        }

        static char character(String quoted) throws EvalException {
            String body = escapes(quoted.substring(1, quoted.length() - 1));
            if (body.length() != 1) {
                throw new EvalException(quoted + " is not a single character.");
            }
            return body.charAt(0);
        }

        private static String escapes(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != '\\' || i + 1 >= text.length()) {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(++i);
                out.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '0' -> '\0';
                    default -> escaped;
                });
            }
            return out.toString();
        }

        private Literals() {
        }
    }

    /** The operators, with Java's promotions: int unless something wider is involved. */
    private static final class Operators {

        static Value binary(VirtualMachine vm, JdiEvaluator evaluator, String op,
                            Value left, Value right) throws EvalException {
            boolean references = left == null || right == null
                    || left instanceof ObjectReference || right instanceof ObjectReference;
            if ((op.equals("==") || op.equals("!=")) && references) {
                /* Reference identity, as Java's == is - two equal strings in different
                   objects are not ==, and a debugger that pretended otherwise would hide
                   the very bug somebody is looking for. */
                boolean same = left == null ? right == null : left.equals(right);
                return vm.mirrorOf(op.equals("==") == same);
            }
            if (op.equals("+") && (isText(left) || isText(right))) {
                return vm.mirrorOf(evaluator.text(left) + evaluator.text(right));
            }
            if (left instanceof BooleanValue a && right instanceof BooleanValue b) {
                return switch (op) {
                    case "==" -> vm.mirrorOf(a.value() == b.value());
                    case "!=" -> vm.mirrorOf(a.value() != b.value());
                    default -> throw new EvalException("Cannot use " + op + " on booleans.");
                };
            }
            if (!(left instanceof PrimitiveValue a) || !(right instanceof PrimitiveValue b)
                    || left instanceof BooleanValue || right instanceof BooleanValue) {
                throw new EvalException("Cannot use " + op + " on " + name(left)
                        + " and " + name(right) + ".");
            }
            if (isFloating(left) || isFloating(right)) {
                return floating(vm, op, a.doubleValue(), b.doubleValue());
            }
            boolean ints = !(left instanceof LongValue || right instanceof LongValue);
            return integral(vm, op, a.longValue(), b.longValue(), ints);
        }

        static Value negate(VirtualMachine vm, Value value) throws EvalException {
            if (isFloating(value)) {
                return vm.mirrorOf(-((PrimitiveValue) value).doubleValue());
            }
            if (value instanceof LongValue number) {
                return vm.mirrorOf(-number.longValue());
            }
            if (value instanceof PrimitiveValue number && !(value instanceof BooleanValue)) {
                return vm.mirrorOf(-number.intValue());
            }
            throw new EvalException("Cannot negate " + name(value) + ".");
        }

        static Value not(VirtualMachine vm, Value value) throws EvalException {
            if (value instanceof BooleanValue bool) {
                return vm.mirrorOf(!bool.value());
            }
            throw new EvalException("Cannot use ! on " + name(value) + ".");
        }

        private static Value floating(VirtualMachine vm, String op, double a, double b)
                throws EvalException {
            return switch (op) {
                case "+" -> vm.mirrorOf(a + b);
                case "-" -> vm.mirrorOf(a - b);
                case "*" -> vm.mirrorOf(a * b);
                case "/" -> vm.mirrorOf(a / b);
                case "%" -> vm.mirrorOf(a % b);
                case "<" -> vm.mirrorOf(a < b);
                case "<=" -> vm.mirrorOf(a <= b);
                case ">" -> vm.mirrorOf(a > b);
                case ">=" -> vm.mirrorOf(a >= b);
                case "==" -> vm.mirrorOf(a == b);
                case "!=" -> vm.mirrorOf(a != b);
                default -> throw new EvalException("Unknown operator " + op + ".");
            };
        }

        private static Value integral(VirtualMachine vm, String op, long a, long b, boolean ints)
                throws EvalException {
            if ((op.equals("/") || op.equals("%")) && b == 0) {
                throw new EvalException("Division by zero.");
            }
            return switch (op) {
                case "+" -> number(vm, a + b, ints);
                case "-" -> number(vm, a - b, ints);
                case "*" -> number(vm, a * b, ints);
                case "/" -> number(vm, a / b, ints);
                case "%" -> number(vm, a % b, ints);
                case "<" -> vm.mirrorOf(a < b);
                case "<=" -> vm.mirrorOf(a <= b);
                case ">" -> vm.mirrorOf(a > b);
                case ">=" -> vm.mirrorOf(a >= b);
                case "==" -> vm.mirrorOf(a == b);
                case "!=" -> vm.mirrorOf(a != b);
                default -> throw new EvalException("Unknown operator " + op + ".");
            };
        }

        private static Value number(VirtualMachine vm, long result, boolean ints) {
            return ints ? vm.mirrorOf((int) result) : vm.mirrorOf(result);
        }

        private static boolean isFloating(Value value) {
            return value instanceof DoubleValue || value instanceof FloatValue;
        }

        private static boolean isText(Value value) {
            return value instanceof StringReference;
        }

        private static String name(Value value) {
            return value == null ? "null" : value.type().name();
        }

        private Operators() {
        }
    }
}
