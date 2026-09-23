package com.tomppi.enderslicer.profile

import java.util.Locale
import kotlin.math.abs

internal object CuraExpressionResolver {
    data class Result(
        val globalValues: Map<String, String>,
        val extruderValues: Map<String, String>,
        val unresolvedExpressions: List<String>,
    )

    fun resolve(
        globalValues: Map<String, String>,
        extruderValues: Map<String, String>,
    ): Result {
        val global = linkedMapOf<String, String>()
        val extruder = linkedMapOf<String, String>()
        val expressions = mutableListOf<Expression>()
        val globalVariables = linkedMapOf<String, Double>()
        val extruderVariables = linkedMapOf<String, Double>()

        fun variables(scope: Scope): MutableMap<String, Double> = when (scope) {
            Scope.GLOBAL -> globalVariables
            Scope.EXTRUDER -> extruderVariables
        }

        fun collect(scope: Scope, input: Map<String, String>, output: MutableMap<String, String>) {
            input.forEach { (key, rawValue) ->
                val value = rawValue.trim()
                if (value.startsWith("=")) {
                    expressions += Expression(scope, key, value.removePrefix("=").trim())
                } else {
                    output[key] = rawValue
                    value.toDoubleOrNull()?.let { variables(scope)[key] = it }
                }
            }
        }

        collect(Scope.GLOBAL, globalValues, global)
        collect(Scope.EXTRUDER, extruderValues, extruder)

        val unresolved = expressions.toMutableList()
        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = unresolved.iterator()
            while (iterator.hasNext()) {
                val expression = iterator.next()
                val variables = variables(expression.scope)
                val result = ArithmeticParser(expression.text, variables).parse() ?: continue
                val formatted = format(result)
                when (expression.scope) {
                    Scope.GLOBAL -> global[expression.key] = formatted
                    Scope.EXTRUDER -> extruder[expression.key] = formatted
                }
                variables[expression.key] = result
                iterator.remove()
                madeProgress = true
            }
        } while (madeProgress)

        return Result(
            globalValues = global,
            extruderValues = extruder,
            unresolvedExpressions = unresolved.map { "${it.scope.label}.${it.key}" },
        )
    }

    private enum class Scope(val label: String) {
        GLOBAL("global"),
        EXTRUDER("extruder"),
    }

    private data class Expression(
        val scope: Scope,
        val key: String,
        val text: String,
    )

    private class ArithmeticParser(
        private val source: String,
        private val variables: Map<String, Double>,
    ) {
        private var index = 0

        fun parse(): Double? {
            if (source.isBlank()) return null
            if (source.any { it == '\'' || it == '"' || it == '?' || it == ':' }) return null
            return runCatching {
                val value = parseExpression()
                skipWhitespace()
                check(index == source.length)
                check(value.isFinite())
                value
            }.getOrNull()
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('+') -> value + parseTerm()
                    consume('-') -> value - parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('*') -> value * parseFactor()
                    consume('/') -> value / parseFactor()
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            if (consume('+')) return parseFactor()
            if (consume('-')) return -parseFactor()
            if (consume('(')) {
                val value = parseExpression()
                skipWhitespace()
                check(consume(')'))
                return value
            }

            if (index < source.length && (source[index].isDigit() || source[index] == '.')) {
                return parseNumber()
            }

            val identifier = parseIdentifier()
            check(identifier.isNotEmpty())
            return variables[identifier] ?: error("Unknown variable: $identifier")
        }

        private fun parseNumber(): Double {
            val start = index
            var sawExponent = false
            while (index < source.length) {
                val character = source[index]
                if (character.isDigit() || character == '.') {
                    index++
                } else if ((character == 'e' || character == 'E') && !sawExponent) {
                    sawExponent = true
                    index++
                    if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                } else {
                    break
                }
            }
            return source.substring(start, index).toDouble()
        }

        private fun parseIdentifier(): String {
            val start = index
            if (index < source.length && (source[index].isLetter() || source[index] == '_')) {
                index++
                while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
            }
            return source.substring(start, index)
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun consume(character: Char): Boolean {
            if (index < source.length && source[index] == character) {
                index++
                return true
            }
            return false
        }
    }

    private fun format(value: Double): String {
        val nearestInteger = value.toLong()
        if (abs(value - nearestInteger) < 1e-9) return nearestInteger.toString()
        return String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
    }
}
