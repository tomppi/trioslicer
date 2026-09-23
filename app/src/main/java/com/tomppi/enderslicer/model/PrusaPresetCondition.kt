package com.tomppi.enderslicer.model

/**
 * The condition language of PrusaSlicer's preset repository.
 *
 * A variant branch carries a condition over the hardware and the preset being evaluated, e.g.
 *
 *     printer.base_model == "MK4"
 *     printer.base_model =~ /(COREONE|MK4|XL)/
 *     tool.nozzle_diameter == 0.4 and ! tool.nozzle_high_flow
 *     printer.supports_high_flow_nozzle and (no MMU fitted or feeder.single_mode)
 *
 * Operands are dotted lookups, numbers, quoted strings and (after the match operators)
 * slash-delimited regular expressions. A bare lookup is true when its value is truthy, so
 * 'tool.nozzle_high_flow' alone asks whether that hardware feature is on. An unset lookup is
 * false for a match and true for a non-match, which is what makes the bundle's MMU test mean
 * "no MMU fitted".
 *
 * The repository uses 138 distinct conditions; [PrusaPresetCatalogSnapshotTest] parses all of
 * them out of the shipped bundle.
 */
object PrusaPresetCondition {

    /** A compiled condition. Evaluation is a lookup, so one parse serves every hardware config. */
    class Expression internal constructor(
        val source: String,
        private val root: Node,
    ) {
        fun matches(lookup: (String) -> Any?): Boolean = root.evaluate(lookup)
        override fun toString(): String = source
    }

    /**
     * Parses one condition.
     *
     * @return null for a blank condition (an unconditional branch).
     * @throws IllegalArgumentException when the text is not a condition this language covers.
     */
    fun parse(text: String?): Expression? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val tokens = Tokenizer(trimmed).tokens()
        val parser = Parser(tokens, trimmed)
        val root = parser.expression()
        parser.expectEnd()
        return Expression(trimmed, root)
    }

    // --- syntax tree -----------------------------------------------------------------------

    internal sealed interface Node {
        fun evaluate(lookup: (String) -> Any?): Boolean
    }

    internal class Or(val items: List<Node>) : Node {
        override fun evaluate(lookup: (String) -> Any?) = items.any { it.evaluate(lookup) }
    }

    internal class And(val items: List<Node>) : Node {
        override fun evaluate(lookup: (String) -> Any?) = items.all { it.evaluate(lookup) }
    }

    internal class Not(val item: Node) : Node {
        override fun evaluate(lookup: (String) -> Any?) = !item.evaluate(lookup)
    }

    internal class Truthy(val operand: Operand) : Node {
        override fun evaluate(lookup: (String) -> Any?) = operand.value(lookup).isTruthy()
    }

    internal class Comparison(val left: Operand, val operator: String, val right: Operand) : Node {
        override fun evaluate(lookup: (String) -> Any?): Boolean {
            val leftValue = left.value(lookup)
            val rightValue = right.value(lookup)
            return when (operator) {
                "==" -> leftValue.equalsValue(rightValue)
                "!=" -> !leftValue.equalsValue(rightValue)
                "=~" -> leftValue.matchesPattern(rightValue)
                "!~" -> !leftValue.matchesPattern(rightValue)
                ">=", "<=", ">", "<" -> leftValue.compareValue(rightValue, operator)
                else -> false
            }
        }
    }

    internal sealed interface Operand {
        fun value(lookup: (String) -> Any?): Any?
    }

    internal class Path(val path: String) : Operand {
        override fun value(lookup: (String) -> Any?) = lookup(path)
    }

    internal class Literal(val literal: Any?) : Operand {
        override fun value(lookup: (String) -> Any?) = literal
    }

    internal class Pattern(val regex: Regex) : Operand {
        override fun value(lookup: (String) -> Any?) = regex
    }

    // --- comparisons -----------------------------------------------------------------------

    private fun Any?.matchesPattern(right: Any?): Boolean {
        val regex = right as? Regex ?: return false
        val text = this?.toString() ?: return false
        return regex.matches(text)
    }

    private fun Any?.equalsValue(other: Any?): Boolean {
        if (this == null || other == null) return this == null && other == null
        val leftNumber = this.asNumber()
        val rightNumber = other.asNumber()
        if (leftNumber != null && rightNumber != null) return leftNumber == rightNumber
        if (this is Boolean && other is Boolean) return this == other
        return this.toString() == other.toString()
    }

    private fun Any?.compareValue(other: Any?, operator: String): Boolean {
        if (this == null || other == null) return false
        val leftNumber = this.asNumber()
        val rightNumber = other.asNumber()
        val comparison = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            this.toString().compareTo(other.toString())
        }
        return when (operator) {
            ">=" -> comparison >= 0
            "<=" -> comparison <= 0
            ">" -> comparison > 0
            "<" -> comparison < 0
            else -> false
        }
    }

    private fun Any?.asNumber(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    private fun Any?.isTruthy(): Boolean = when (this) {
        null -> false
        is Boolean -> this
        is Number -> toDouble() != 0.0
        is String -> isNotEmpty() && this != "0" && !equals("false", ignoreCase = true)
        is Collection<*> -> isNotEmpty()
        else -> true
    }

    // --- lexer -----------------------------------------------------------------------------

    private sealed interface Token {
        val position: Int

        data class Symbol(override val position: Int, val text: String) : Token
        data class Word(override val position: Int, val text: String) : Token
        data class Text(override val position: Int, val text: String) : Token
        data class Number(override val position: Int, val value: Double) : Token
        data class RegexToken(override val position: Int, val pattern: String) : Token
    }

    private class Tokenizer(private val text: String) {
        private var index = 0

        fun tokens(): List<Token> {
            val result = mutableListOf<Token>()
            while (index < text.length) {
                val character = text[index]
                when {
                    character.isWhitespace() -> index++
                    character == '(' || character == ')' -> {
                        result.add(Token.Symbol(index, character.toString()))
                        index++
                    }
                    text.startsWith("=~", index) || text.startsWith("!~", index) -> {
                        val position = index
                        index += 2
                        result.add(Token.Symbol(position, text.substring(position, index)))
                    }
                    text.startsWith("==", index) || text.startsWith("!=", index) ||
                        text.startsWith(">=", index) || text.startsWith("<=", index) -> {
                        val position = index
                        index += 2
                        result.add(Token.Symbol(position, text.substring(position, index)))
                    }
                    character == '>' || character == '<' || character == '!' -> {
                        result.add(Token.Symbol(index, character.toString()))
                        index++
                    }
                    character == '/' -> {
                        val position = index
                        val end = text.indexOf('/', index + 1)
                        require(end > index) { "Unterminated regular expression at " + position }
                        result.add(Token.RegexToken(position, text.substring(index + 1, end)))
                        index = end + 1
                    }
                    character == '"' || character == '\'' -> {
                        val quote = character
                        val position = index
                        val builder = StringBuilder()
                        index++
                        while (index < text.length && text[index] != quote) {
                            builder.append(text[index])
                            index++
                        }
                        require(index < text.length) { "Unterminated string at " + position }
                        index++
                        result.add(Token.Text(position, builder.toString()))
                    }
                    character.isDigit() || (character == '-' && text.getOrNull(index + 1)?.isDigit() == true) -> {
                        val position = index
                        if (character == '-') index++
                        while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
                        result.add(Token.Number(position, text.substring(position, index).toDouble()))
                    }
                    character.isLetter() || character == '_' -> {
                        val position = index
                        while (index < text.length &&
                            (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '.')
                        ) {
                            index++
                        }
                        result.add(Token.Word(position, text.substring(position, index)))
                    }
                    else -> throw IllegalArgumentException(
                        "Unexpected character '" + character + "' at " + index + " in: " + text,
                    )
                }
            }
            return result
        }
    }

    // --- parser ----------------------------------------------------------------------------

    private class Parser(private val tokens: List<Token>, private val source: String) {
        private var index = 0

        fun expression(): Node = parseOr()

        fun expectEnd() {
            require(index == tokens.size) {
                "Trailing tokens in condition: " + source
            }
        }

        private fun parseOr(): Node {
            val items = mutableListOf(parseAnd())
            while (peekWord("or")) {
                index++
                items.add(parseAnd())
            }
            return if (items.size == 1) items.first() else Or(items)
        }

        private fun parseAnd(): Node {
            val items = mutableListOf(parseUnary())
            while (peekWord("and")) {
                index++
                items.add(parseUnary())
            }
            return if (items.size == 1) items.first() else And(items)
        }

        private fun parseUnary(): Node {
            if (peekWord("not") || peekSymbol("!")) {
                index++
                return Not(parseUnary())
            }
            if (peekSymbol("(")) {
                index++
                val inner = parseOr()
                require(peekSymbol(")")) { "Missing ) in condition: " + source }
                index++
                return inner
            }
            return parseComparison()
        }

        private fun parseComparison(): Node {
            val left = parseOperand()
            val operator = when (val token = tokens.getOrNull(index)) {
                is Token.Symbol -> token.text.takeIf {
                    it == "==" || it == "!=" || it == "=~" || it == "!~" ||
                        it == ">=" || it == "<=" || it == ">" || it == "<"
                }
                else -> null
            } ?: return Truthy(left)
            index++
            val right = parseOperand()
            return Comparison(left, operator, right)
        }

        private fun parseOperand(): Operand {
            val token = tokens.getOrNull(index) ?: throw IllegalArgumentException(
                "Unexpected end of condition: " + source,
            )
            index++
            return when (token) {
                is Token.Number -> Literal(token.value)
                is Token.Text -> Literal(token.text)
                is Token.RegexToken -> Pattern(Regex(token.pattern))
                is Token.Word -> when (token.text) {
                    "true" -> Literal(true)
                    "false" -> Literal(false)
                    else -> Path(token.text)
                }
                is Token.Symbol -> throw IllegalArgumentException(
                    "Unexpected '" + token.text + "' in condition: " + source,
                )
            }
        }

        private fun peekWord(word: String): Boolean =
            (tokens.getOrNull(index) as? Token.Word)?.text == word

        private fun peekSymbol(symbol: String): Boolean =
            (tokens.getOrNull(index) as? Token.Symbol)?.text == symbol
    }
}
