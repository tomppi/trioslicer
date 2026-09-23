package com.tomppi.enderslicer.profile

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.*

internal sealed interface CuraExpression {
    fun eval(context: CuraEvaluationContext): Any?
}

private data class LiteralExpr(val value: Any?) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? = value
}

private data class VariableExpr(val name: String) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? = context.variable(name)
}

private data class FunctionRef(val name: String)

private data class UnaryExpr(val op: TokenType, val operand: CuraExpression) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? {
        val value = operand.eval(context)
        return when (op) {
            TokenType.PLUS -> number(value)
            TokenType.MINUS -> -number(value)
            TokenType.NOT -> !truthy(value)
            else -> error("Unsupported unary operator: $op")
        }
    }
}

private data class BinaryExpr(val left: CuraExpression, val op: TokenType, val right: CuraExpression) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? {
        return when (op) {
            TokenType.AND -> {
                val lhs = left.eval(context)
                if (!truthy(lhs)) false else truthy(right.eval(context))
            }
            TokenType.OR -> {
                val lhs = left.eval(context)
                if (truthy(lhs)) true else truthy(right.eval(context))
            }
            else -> {
                val lhs = left.eval(context)
                val rhs = right.eval(context)
                when (op) {
                    TokenType.PLUS -> plus(lhs, rhs)
                    TokenType.MINUS -> number(lhs) - number(rhs)
                    TokenType.STAR -> number(lhs) * number(rhs)
                    TokenType.SLASH -> number(lhs) / number(rhs)
                    TokenType.POWER -> number(lhs).pow(number(rhs))
                    TokenType.FLOOR_DIV -> floor(number(lhs) / number(rhs))
                    TokenType.PERCENT -> modulo(number(lhs), number(rhs))
                    TokenType.EQ -> equalValues(lhs, rhs)
                    TokenType.NE -> !equalValues(lhs, rhs)
                    TokenType.LT -> compareValues(lhs, rhs) < 0
                    TokenType.LE -> compareValues(lhs, rhs) <= 0
                    TokenType.GT -> compareValues(lhs, rhs) > 0
                    TokenType.GE -> compareValues(lhs, rhs) >= 0
                    TokenType.IN -> containsValue(rhs, lhs)
                    TokenType.NOT_IN -> !containsValue(rhs, lhs)
                    else -> error("Unsupported binary operator: $op")
                }
            }
        }
    }
}

private data class ConditionalExpr(val whenTrue: CuraExpression, val condition: CuraExpression, val whenFalse: CuraExpression) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? = if (truthy(condition.eval(context))) {
        whenTrue.eval(context)
    } else {
        whenFalse.eval(context)
    }
}

private data class ListExpr(val values: List<CuraExpression>) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? = values.map { it.eval(context) }
}

private data class CallExpr(val name: String, val arguments: List<CuraExpression>) : CuraExpression {
    override fun eval(context: CuraEvaluationContext): Any? {
        val args = arguments.map { it.eval(context) }
        return context.call(name, args)
    }
}

internal class CuraEvaluationContext(
    private val localValues: Map<String, Any?>,
    private val globalValues: Map<String, Any?>,
    private val extruderValues: Map<String, Any?>,
) {
    fun variable(name: String): Any? {
        if (name == "True") return true
        if (name == "False") return false
        if (name == "None") return null
        if (name == "math.pi") return Math.PI
        if (name == "math.e") return Math.E
        if (name in BUILTIN_FUNCTIONS) return FunctionRef(name)
        return localValues[name] ?: globalValues[name] ?: error("Unknown variable: $name")
    }

    fun call(name: String, args: List<Any?>): Any? = when (name) {
        "resolveOrValue" -> resolve(args.string(0))
        "extruderValue" -> extruderValues[args.string(1)] ?: globalValues[args.string(1)]
            ?: error("Unknown extruder setting: ${args.string(1)}")
        "extruderValues" -> listOf(extruderValues[args.string(0)] ?: globalValues[args.string(0)]
            ?: error("Unknown extruder setting: ${args.string(0)}"))
        "anyExtruderWithMaterial", "defaultExtruderPosition" -> 0L
        "max" -> numericAggregate(args, ::max)
        "min" -> numericAggregate(args, ::min)
        "sum" -> flattenSingleList(args).sumOf(::number)
        "len" -> when (val value = args.firstOrNull()) {
            is Collection<*> -> value.size.toLong()
            is String -> value.length.toLong()
            else -> error("len() requires a list or string")
        }
        "round" -> {
            val value = number(args.getOrNull(0))
            val digits = args.getOrNull(1)?.let(::number)?.toInt() ?: 0
            BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
        }
        "int" -> number(args.getOrNull(0)).toLong()
        "float" -> number(args.getOrNull(0))
        "abs" -> abs(number(args.getOrNull(0)))
        "map" -> {
            val function = args.getOrNull(0) as? FunctionRef ?: error("map() requires a function")
            val values = args.getOrNull(1) as? Collection<*> ?: error("map() requires a collection")
            values.map { call(function.name, listOf(it)) }
        }
        "math.ceil" -> ceil(number(args.getOrNull(0))).toLong()
        "math.floor" -> floor(number(args.getOrNull(0))).toLong()
        "math.tan" -> tan(number(args.getOrNull(0)))
        "math.radians" -> Math.toRadians(number(args.getOrNull(0)))
        "math.degrees" -> Math.toDegrees(number(args.getOrNull(0)))
        "math.atan" -> atan(number(args.getOrNull(0)))
        "math.atan2" -> atan2(number(args.getOrNull(0)), number(args.getOrNull(1)))
        "math.sin" -> sin(number(args.getOrNull(0)))
        "math.cos" -> cos(number(args.getOrNull(0)))
        "math.sinh" -> sinh(number(args.getOrNull(0)))
        "math.cosh" -> cosh(number(args.getOrNull(0)))
        "math.tanh" -> tanh(number(args.getOrNull(0)))
        "math.asin" -> {
            val value = number(args.getOrNull(0))
            require(value in -1.0..1.0) { "math.asin domain error" }
            asin(value)
        }
        "math.acos" -> {
            val value = number(args.getOrNull(0))
            require(value in -1.0..1.0) { "math.acos domain error" }
            acos(value)
        }
        "math.sqrt" -> {
            val value = number(args.getOrNull(0))
            require(value >= 0.0) { "math.sqrt domain error" }
            sqrt(value)
        }
        "math.exp" -> exp(number(args.getOrNull(0)))
        "math.log" -> {
            val value = number(args.getOrNull(0))
            require(value > 0.0) { "math.log domain error" }
            ln(value)
        }
        "math.log10" -> {
            val value = number(args.getOrNull(0))
            require(value > 0.0) { "math.log10 domain error" }
            log10(value)
        }
        "math.log2" -> {
            val value = number(args.getOrNull(0))
            require(value > 0.0) { "math.log2 domain error" }
            log2(value)
        }
        "math.pow" -> number(args.getOrNull(0)).pow(number(args.getOrNull(1)))
        "math.hypot" -> hypot(number(args.getOrNull(0)), number(args.getOrNull(1)))
        else -> error("Unsupported function: $name")
    }

    private fun resolve(key: String): Any? = localValues[key] ?: globalValues[key]
        ?: error("Unknown setting: $key")

    private fun numericAggregate(args: List<Any?>, operation: (Double, Double) -> Double): Double {
        val values = flattenSingleList(args).map(::number)
        require(values.isNotEmpty()) { "Aggregate requires at least one value" }
        return values.drop(1).fold(values.first(), operation)
    }

    private fun flattenSingleList(args: List<Any?>): List<Any?> {
        return if (args.size == 1 && args[0] is Collection<*>) {
            (args[0] as Collection<*>).toList()
        } else {
            args
        }
    }

    private fun List<Any?>.string(index: Int): String = getOrNull(index) as? String
        ?: error("Argument $index must be a string")

    private companion object {
        val BUILTIN_FUNCTIONS = setOf("abs")
    }
}

/**
 * How deep a parsed formula may nest.
 *
 * Well above the deepest bundled Cura formula and far below what the JVM stack
 * can take, so a peer-authored expression is rejected by name instead of
 * overflowing the stack.
 */
private const val MAX_EXPRESSION_DEPTH = 64

internal object CuraValueExpressionParser {
    fun parse(source: String): CuraExpression = Parser(Lexer(source).tokens()).parse()
}

private class Parser(private val tokens: List<Token>) {
    private var index = 0
    private var depth = 0

    fun parse(): CuraExpression {
        val expression = parseConditional()
        expect(TokenType.EOF)
        return expression
    }

    /**
     * Counts one level of recursion, refusing anything past [MAX_EXPRESSION_DEPTH].
     *
     * The source is peer-authored - a Cura profile's own formulas arrive with
     * it - so the nesting depth is attacker-controlled. Recursing without a
     * limit overflows the stack, and the importer used to swallow that as a
     * warning and carry on with a profile whose settings were never resolved.
     * The limit counts grammar levels (a bracket, an argument, an else branch
     * or a unary operator each take one), so it is far above any real formula.
     */
    private fun enterDepth() {
        if (depth >= MAX_EXPRESSION_DEPTH) {
            error("Cura expression nests deeper than $MAX_EXPRESSION_DEPTH levels")
        }
        depth++
    }

    private fun parseConditional(): CuraExpression {
        enterDepth()
        try {
            val whenTrue = parseOr()
            if (!match(TokenType.IF)) return whenTrue
            val condition = parseOr()
            expect(TokenType.ELSE)
            val whenFalse = parseConditional()
            return ConditionalExpr(whenTrue, condition, whenFalse)
        } finally {
            depth--
        }
    }

    private fun parseOr(): CuraExpression {
        var expression = parseAnd()
        while (match(TokenType.OR)) expression = BinaryExpr(expression, TokenType.OR, parseAnd())
        return expression
    }

    private fun parseAnd(): CuraExpression {
        var expression = parseNot()
        while (match(TokenType.AND)) expression = BinaryExpr(expression, TokenType.AND, parseNot())
        return expression
    }

    private fun parseNot(): CuraExpression {
        if (match(TokenType.NOT)) {
            if (match(TokenType.IN)) error("Unexpected 'not in' without a left operand")
            return UnaryExpr(TokenType.NOT, parseComparison())
        }
        return parseComparison()
    }

    private fun parseComparison(): CuraExpression {
        var expression = parseAdditive()
        while (true) {
            val operator = when {
                match(TokenType.EQ) -> TokenType.EQ
                match(TokenType.NE) -> TokenType.NE
                match(TokenType.LE) -> TokenType.LE
                match(TokenType.LT) -> TokenType.LT
                match(TokenType.GE) -> TokenType.GE
                match(TokenType.GT) -> TokenType.GT
                match(TokenType.IN) -> TokenType.IN
                check(TokenType.NOT) && checkNext(TokenType.IN) -> {
                    advance(); advance(); TokenType.NOT_IN
                }
                else -> return expression
            }
            expression = BinaryExpr(expression, operator, parseAdditive())
        }
    }

    private fun parseAdditive(): CuraExpression {
        var expression = parseMultiplicative()
        while (true) {
            expression = when {
                match(TokenType.PLUS) -> BinaryExpr(expression, TokenType.PLUS, parseMultiplicative())
                match(TokenType.MINUS) -> BinaryExpr(expression, TokenType.MINUS, parseMultiplicative())
                else -> return expression
            }
        }
    }

    private fun parseMultiplicative(): CuraExpression {
        var expression = parseUnary()
        while (true) {
            expression = when {
                match(TokenType.STAR) -> BinaryExpr(expression, TokenType.STAR, parseUnary())
                match(TokenType.SLASH) -> BinaryExpr(expression, TokenType.SLASH, parseUnary())
                match(TokenType.FLOOR_DIV) -> BinaryExpr(expression, TokenType.FLOOR_DIV, parseUnary())
                match(TokenType.PERCENT) -> BinaryExpr(expression, TokenType.PERCENT, parseUnary())
                else -> return expression
            }
        }
    }

    private fun parseUnary(): CuraExpression {
        enterDepth()
        try {
            return when {
                match(TokenType.PLUS) -> UnaryExpr(TokenType.PLUS, parseUnary())
                match(TokenType.MINUS) -> UnaryExpr(TokenType.MINUS, parseUnary())
                else -> parsePower()
            }
        } finally {
            depth--
        }
    }

    private fun parsePower(): CuraExpression {
        var expression = parsePrimary()
        if (match(TokenType.POWER)) {
            expression = BinaryExpr(expression, TokenType.POWER, parseUnary())
        }
        return expression
    }

    private fun parsePrimary(): CuraExpression {
        if (match(TokenType.NUMBER)) return LiteralExpr(previous().text.toDouble())
        if (match(TokenType.STRING)) return LiteralExpr(previous().literal)
        if (match(TokenType.TRUE)) return LiteralExpr(true)
        if (match(TokenType.FALSE)) return LiteralExpr(false)
        if (match(TokenType.NONE)) return LiteralExpr(null)
        if (match(TokenType.LEFT_BRACKET)) {
            val values = mutableListOf<CuraExpression>()
            if (!check(TokenType.RIGHT_BRACKET)) {
                do values += parseConditional() while (match(TokenType.COMMA))
            }
            expect(TokenType.RIGHT_BRACKET)
            return ListExpr(values)
        }
        if (match(TokenType.LEFT_PAREN)) {
            val expression = parseConditional()
            expect(TokenType.RIGHT_PAREN)
            return expression
        }
        if (match(TokenType.IDENTIFIER)) {
            var name = previous().text
            while (match(TokenType.DOT)) {
                name += "." + expect(TokenType.IDENTIFIER).text
            }
            if (match(TokenType.LEFT_PAREN)) {
                val arguments = mutableListOf<CuraExpression>()
                if (!check(TokenType.RIGHT_PAREN)) {
                    do arguments += parseConditional() while (match(TokenType.COMMA))
                }
                expect(TokenType.RIGHT_PAREN)
                return CallExpr(name, arguments)
            }
            return VariableExpr(name)
        }
        error("Unexpected token ${peek().type} at ${peek().position}")
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun check(type: TokenType): Boolean = peek().type == type
    private fun checkNext(type: TokenType): Boolean = tokens.getOrElse(index + 1) { tokens.last() }.type == type
    private fun advance(): Token = tokens[index++]
    private fun previous(): Token = tokens[index - 1]
    private fun peek(): Token = tokens[index]
    private fun expect(type: TokenType): Token {
        if (!check(type)) error("Expected $type at ${peek().position}, found ${peek().type}")
        return advance()
    }
}

private class Lexer(private val source: String) {
    private val result = mutableListOf<Token>()
    private var index = 0

    fun tokens(): List<Token> {
        while (index < source.length) {
            val start = index
            val character = source[index++]
            when {
                character.isWhitespace() -> Unit
                character.isDigit() || (character == '.' && source.getOrNull(index)?.isDigit() == true) -> number(start)
                character.isLetter() || character == '_' -> identifier(start)
                character == '\'' || character == '"' -> string(start, character)
                else -> symbol(start, character)
            }
        }
        result += Token(TokenType.EOF, "", null, source.length)
        return result
    }

    private fun number(start: Int) {
        var sawDot = source[start] == '.'
        while (source.getOrNull(index)?.let { it.isDigit() || (it == '.' && !sawDot) } == true) {
            if (source[index] == '.') sawDot = true
            index++
        }
        if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
            index++
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            while (source.getOrNull(index)?.isDigit() == true) index++
        }
        result += Token(TokenType.NUMBER, source.substring(start, index), null, start)
    }

    private fun identifier(start: Int) {
        while (source.getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' } == true) index++
        val text = source.substring(start, index)
        val type = when (text) {
            "if" -> TokenType.IF
            "else" -> TokenType.ELSE
            "and" -> TokenType.AND
            "or" -> TokenType.OR
            "not" -> TokenType.NOT
            "in" -> TokenType.IN
            "True" -> TokenType.TRUE
            "False" -> TokenType.FALSE
            "None" -> TokenType.NONE
            else -> TokenType.IDENTIFIER
        }
        result += Token(type, text, null, start)
    }

    private fun string(start: Int, quote: Char) {
        val builder = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            if (character == quote) {
                result += Token(TokenType.STRING, source.substring(start, index), builder.toString(), start)
                return
            }
            if (character == '\\' && index < source.length) {
                val escaped = source[index++]
                builder.append(
                    when (escaped) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\\' -> '\\'
                        '\'' -> '\''
                        '"' -> '"'
                        else -> escaped
                    },
                )
            } else {
                builder.append(character)
            }
        }
        error("Unterminated string at $start")
    }

    private fun symbol(start: Int, character: Char) {
        fun add(type: TokenType, text: String = character.toString()) {
            result += Token(type, text, null, start)
        }
        when (character) {
            '+' -> add(TokenType.PLUS)
            '-' -> add(TokenType.MINUS)
            '*' -> if (source.getOrNull(index) == '*') { index++; add(TokenType.POWER, "**") } else add(TokenType.STAR)
            '/' -> if (source.getOrNull(index) == '/') { index++; add(TokenType.FLOOR_DIV, "//") } else add(TokenType.SLASH)
            '%' -> add(TokenType.PERCENT)
            '(' -> add(TokenType.LEFT_PAREN)
            ')' -> add(TokenType.RIGHT_PAREN)
            '[' -> add(TokenType.LEFT_BRACKET)
            ']' -> add(TokenType.RIGHT_BRACKET)
            ',' -> add(TokenType.COMMA)
            '.' -> add(TokenType.DOT)
            '=' -> {
                require(source.getOrNull(index) == '=') { "Expected == at $start" }
                index++; add(TokenType.EQ, "==")
            }
            '!' -> {
                require(source.getOrNull(index) == '=') { "Expected != at $start" }
                index++; add(TokenType.NE, "!=")
            }
            '<' -> if (source.getOrNull(index) == '=') { index++; add(TokenType.LE, "<=") } else add(TokenType.LT)
            '>' -> if (source.getOrNull(index) == '=') { index++; add(TokenType.GE, ">=") } else add(TokenType.GT)
            else -> error("Unexpected character '$character' at $start")
        }
    }
}

private data class Token(val type: TokenType, val text: String, val literal: Any?, val position: Int)

private enum class TokenType {
    NUMBER, STRING, IDENTIFIER,
    TRUE, FALSE, NONE,
    IF, ELSE, AND, OR, NOT, IN, NOT_IN,
    PLUS, MINUS, STAR, SLASH, PERCENT, POWER, FLOOR_DIV,
    EQ, NE, LT, LE, GT, GE,
    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET, RIGHT_BRACKET, COMMA, DOT,
    EOF,
}

private fun number(value: Any?): Double = when (value) {
    is Number -> value.toDouble()
    is Boolean -> if (value) 1.0 else 0.0
    is String -> value.toDoubleOrNull() ?: error("Not a number: $value")
    else -> error("Not a number: $value")
}

private fun truthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    is String -> value.isNotEmpty()
    is Collection<*> -> value.isNotEmpty()
    else -> true
}

private fun plus(left: Any?, right: Any?): Any? = when {
    left is String || right is String -> left.toString() + right.toString()
    left is Collection<*> && right is Collection<*> -> left + right
    else -> number(left) + number(right)
}

private fun modulo(left: Double, right: Double): Double {
    require(right != 0.0) { "modulo by zero" }
    val remainder = left % right
    if (remainder == 0.0) return 0.0
    return if ((remainder < 0.0) != (right < 0.0)) remainder + right else remainder
}

private fun equalValues(left: Any?, right: Any?): Boolean {
    if (left is Number && right is Number) return abs(left.toDouble() - right.toDouble()) < 1e-9
    return left == right
}

private fun compareValues(left: Any?, right: Any?): Int {
    if (left is Number && right is Number) return left.toDouble().compareTo(right.toDouble())
    if (left is String && right is String) return left.compareTo(right)
    error("Values are not comparable: $left and $right")
}

private fun containsValue(container: Any?, value: Any?): Boolean = when (container) {
    is Collection<*> -> container.any { equalValues(it, value) }
    is String -> value is String && value in container
    else -> false
}
