package com.tomppi.enderslicer.profile

/**
 * Whether a Cura setting is in effect, and if not, what it is waiting for.
 *
 * Cura declares this per setting as an `enabled` expression - 516 of the 729 settings in the
 * bundled fdmprinter.def.json carry one, in a small language: setting names, `and`, `or`,
 * `not`, the six comparisons, parentheses and quoted strings. `retraction_hop_only_when_collides`
 * is the case that prompted this:
 *
 *     retraction_enable and retraction_hop_enabled and travel_avoid_other_parts
 *
 * Without all three the setting is inert, and nothing in the app said so. The engine simply never
 * reads it, and the one place a user would look - the list they picked it from - showed it as
 * available as any other.
 *
 * Booleans are read the way CuraEngine reads them (Settings::get<bool>), because a second opinion
 * here would disagree with the engine exactly where it matters.
 */
object CuraEnabled {

    /** Why [expression] is false, or null when it is true or absent. */
    fun inactiveReason(expression: String?, valueOf: (String) -> String?): String? {
        if (expression.isNullOrBlank()) return null
        val expr = runCatching { Parser(expression).parse() }.getOrNull()
            // An expression this cannot read must not be reported as inactive: claiming a
            // setting is switched off would be worse than admitting the expression is unknown.
            ?: return null
        if (eval(expr, valueOf)) return null
        val unmet = dependencies(expr).filter { !truthy(valueOf(it)) }
        return if (unmet.isEmpty()) {
            "not active with the current settings"
        } else {
            "needs " + unmet.sorted().joinToString(", ")
        }
    }

    /**
     * Whether the expression can be read at all. Every one of the 516 in the bundled definitions
     * must be, and a test walks them all: a parser that quietly fails on a shape it has not met
     * would mark a whole group of settings wrongly, and silently.
     */
    fun isReadable(expression: String): Boolean =
        runCatching { Parser(expression).parse() }.isSuccess

    /** Why an expression cannot be read. Only diagnostics read this - the sheet shows nothing. */
    internal fun parseError(expression: String): String? =
        runCatching { Parser(expression).parse() }.exceptionOrNull()?.message

    /** CuraEngine's Settings::get<bool>, so this agrees with the engine rather than with itself. */
    fun truthy(value: String?): Boolean {
        val v = value?.trim() ?: return false
        if (v == "on" || v == "yes" || v == "true" || v == "True") return true
        return (v.toDoubleOrNull() ?: 0.0) != 0.0
    }

    private fun eval(node: Node, valueOf: (String) -> String?): Boolean = when (node) {
        is Node.Name -> truthy(valueOf(node.name))
        is Node.Not -> !eval(node.inner, valueOf)
        is Node.And -> eval(node.left, valueOf) && eval(node.right, valueOf)
        is Node.Or -> eval(node.left, valueOf) || eval(node.right, valueOf)
        is Node.Compare -> {
            val left = valueOf(node.left)?.trim()
            val right = node.right
            val ln = left?.toDoubleOrNull()
            val rn = right.toDoubleOrNull()
            when {
                ln != null && rn != null -> when (node.op) {
                    "==" -> ln == rn; "!=" -> ln != rn
                    "<" -> ln < rn; ">" -> ln > rn; "<=" -> ln <= rn; else -> ln >= rn
                }
                else -> when (node.op) {
                    "==" -> left == right; "!=" -> left != right
                    // Ordering strings means nothing, and saying false is the honest answer.
                    else -> false
                }
            }
        }
    }

    /** Every setting name the expression reads, so the unmet ones can be named back. */
    private fun dependencies(node: Node): List<String> = when (node) {
        is Node.Name -> listOf(node.name)
        is Node.Not -> dependencies(node.inner)
        is Node.And -> dependencies(node.left) + dependencies(node.right)
        is Node.Or -> dependencies(node.left) + dependencies(node.right)
        is Node.Compare -> listOf(node.left)
    }

    private sealed interface Node {
        data class Name(val name: String) : Node
        data class Not(val inner: Node) : Node
        data class And(val left: Node, val right: Node) : Node
        data class Or(val left: Node, val right: Node) : Node
        data class Compare(val left: String, val op: String, val right: String) : Node
    }

    /**
     * Recursive descent over the whole grammar, which is small enough to read in one sitting.
     * Precedence, loosest first: or, and, not, comparison.
     */
    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Node {
            val node = or()
            skipSpace()
            require(pos >= src.length) { "unexpected trailing input at $pos in \"$src\"" }
            return node
        }

        private fun or(): Node {
            var left = and()
            while (true) {
                skipSpace()
                if (!word("or")) return left
                left = Node.Or(left, and())
            }
        }

        private fun and(): Node {
            var left = not()
            while (true) {
                skipSpace()
                if (!word("and")) return left
                left = Node.And(left, not())
            }
        }

        private fun not(): Node {
            skipSpace()
            return if (word("not")) Node.Not(not()) else comparison()
        }

        private fun comparison(): Node {
            skipSpace()
            if (peek() == '(') {
                pos++
                val inner = or()
                skipSpace()
                require(peek() == ')') { "missing ) at $pos" }
                pos++
                return inner
            }
            val name = valueName()
            skipSpace()
            val op = OPERATORS.firstOrNull { src.startsWith(it, pos) }
                ?: return Node.Name(name)
            pos += op.length
            skipSpace()
            return Node.Compare(name, op, literal())
        }

        /**
         * A setting name, or a call that yields one.
         *
         * Cura's expressions also say `resolveOrValue('adhesion_type')`, `extruderValues('x')`,
         * `max(...)` and `min(...)`. This app slices with one extruder and no per-object
         * overrides, so a one-argument helper resolves to the value of its argument - which is
         * what the call means on such a machine. The alternative is refusing to read 30-odd
         * expressions and reporting nothing about the settings they guard.
         */
        private fun valueName(): String {
            skipSpace()
            val quote = peek()
            // A nested call passes its own call as the argument: max(extruderValues('x')).
            val name = if (quote == '"' || quote == '\'') literal() else identifier()
            skipSpace()
            if (peek() != '(') return name
            pos++
            skipSpace()
            val first = valueName()
            skipSpace()
            while (peek() == ',') {
                pos++
                skipSpace()
                valueName()
                skipSpace()
            }
            require(peek() == ')') { "missing ) for $name at $pos" }
            pos++
            return if (name in CALL_HELPERS) first else name
        }

        private fun literal(): String {
            val q = peek()
            if (q == '"' || q == '\'') {
                pos++
                val end = src.indexOf(q, pos)
                require(end >= 0) { "unterminated string at $pos" }
                val text = src.substring(pos, end)
                pos = end + 1
                return text
            }
            val start = pos
            // '_' belongs here: the right-hand side of a comparison is often another setting
            // name - infill_extruder_nr, material_print_temperature - and without it the name
            // was read only as far as the first underscore, leaving the rest as trailing input
            // and the whole expression unreadable. Short synthetic names had no underscores,
            // which is exactly why a test written with them would have missed this.
            while (pos < src.length &&
                (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '.' || src[pos] == '-')
            ) {
                pos++
            }
            require(pos > start) { "expected a value at $pos in \"$src\"" }
            return src.substring(start, pos)
        }

        private fun identifier(): String {
            skipSpace()
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            require(pos > start) { "expected a setting name at $pos in \"$src\"" }
            return src.substring(start, pos)
        }

        /** A whole word, so "order" is not read as "or". */
        private fun word(w: String): Boolean {
            if (!src.startsWith(w, pos)) return false
            val after = pos + w.length
            if (after < src.length && (src[after].isLetterOrDigit() || src[after] == '_')) return false
            pos = after
            return true
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
    }

    private val OPERATORS = listOf("==", "!=", "<=", ">=", "<", ">")

    /**
     * Calls whose one argument is the setting they report on. `extruders_enabled_count` is not
     * here: it is a plain name the caller supplies, not a setting in the definitions.
     */
    private val CALL_HELPERS = setOf("resolveOrValue", "extruderValues", "max", "min")
}
