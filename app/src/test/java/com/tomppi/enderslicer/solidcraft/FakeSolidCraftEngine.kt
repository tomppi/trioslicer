package com.tomppi.enderslicer.solidcraft

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A scripted SolidCraft engine for the JVM tests: a real child process speaking the same
 * newline-delimited JSON-RPC 2.0 protocol, with the same body report shapes the engine was
 * verified to send (created_bodies for a box, changed_bodies for a fillet).
 *
 * It is a separate process on purpose: the client's framing, timeouts and end-of-stream
 * behaviour are exactly what a child process makes awkward, so the tests run against one.
 * The process exits when its stdin closes.
 */
class FakeSolidCraftEngine(args: Array<String>) {

    private val stdin = System.`in`.bufferedReader(Charsets.UTF_8)
    private val stdout = System.out

    fun run() {
        while (true) {
            val line = stdin.readLine() ?: return
            if (line.isBlank()) continue
            handleLine(line)
        }
    }

    private fun handleLine(line: String) {
        val message = try {
            JSONObject(line)
        } catch (_: Throwable) {
            write(
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                    .put("error", JSONObject().put("code", -32700).put("message", "parse error")),
            )
            return
        }
        val method = message.optString("method")
        val id = message.opt("id")
        if (method.isBlank()) return
        if (id == null) return
        val params = message.optJSONObject("params") ?: JSONObject()
        val reply = when (method) {
            "initialize" -> result(
                id,
                JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("capabilities", JSONObject())
                    .put("serverInfo", JSONObject().put("name", "solidcraft").put("version", "0.24.0"))
                    .put("instructions", "fake engine"),
            )
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(
                id,
                JSONObject().put(
                    "tools",
                    JSONArray().apply {
                        listOf(
                            "list_commands", "describe_command", "execute", "batch", "inspect_design",
                            "measure", "body_topology", "set_parameter", "screenshot", "export",
                            "crash_reports", "running", "pending", "undo", "redo", "new_design",
                            "open", "save", "skill_reference",
                        ).forEach { name -> put(JSONObject().put("name", name)) }
                    },
                ),
            )
            "tools/call" -> toolCall(id, params)
            else -> error(id, -32601, "method " + method + " not found")
        }
        write(reply)
    }

    private fun toolCall(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return when (name) {
            "execute" -> {
                val command = args.optString("command")
                when (command) {
                    "solid.box" -> result(
                        id,
                        toolResult(
                            JSONObject()
                                .put("bodies", JSONArray().put("Body1"))
                                .put("created_bodies", JSONArray().put("Body1"))
                                .put("changed_bodies", JSONArray())
                                .put("removed_bodies", JSONArray())
                                .put("feature", 1)
                                .put("name", "Box1"),
                        ),
                    )
                    "solid.fillet" -> result(
                        id,
                        toolResult(
                            JSONObject()
                                .put("bodies", JSONArray().put("Body1"))
                                .put("created_bodies", JSONArray())
                                .put("changed_bodies", JSONArray().put("Body1"))
                                .put("removed_bodies", JSONArray())
                                .put("feature", 2)
                                .put("name", "Fillet1"),
                        ),
                    )
                    else -> result(id, toolError("unknown command " + command))
                }
            }
            "batch" -> {
                val commands = args.optJSONArray("commands") ?: JSONArray()
                val results = JSONArray()
                var failed = false
                for (index in 0 until commands.length()) {
                    val step = commands.optJSONObject(index) ?: continue
                    val command = step.optString("command")
                    if (command == "file.export") {
                        writeStl(step.optJSONObject("params")?.optString("path"))
                        results.put(JSONObject().put("bytes", 50).put("format", "StlBinary"))
                    } else {
                        failed = true
                        results.put(JSONObject().put("error", "unknown command " + command))
                    }
                }
                result(
                    id,
                    toolResult(
                        JSONObject()
                            .put("ok", !failed)
                            .put("completed", results.length())
                            .put("results", results),
                    ),
                )
            }
            "inspect_design" -> result(
                id,
                toolResult(
                    JSONObject()
                        .put("name", "Untitled")
                        .put("bodies", JSONArray().put(JSONObject().put("name", "Body1").put("visible", true)))
                        .put("dirty", true),
                ),
            )
            "export" -> {
                val path = args.optString("path")
                writeStl(path)
                result(
                    id,
                    toolResult(JSONObject().put("bytes", 50).put("format", "StlBinary").put("path", path)),
                )
            }
            "save" -> result(
                id,
                toolResult(JSONObject().put("name", "Untitled").put("path", args.optString("path"))),
            )
            "open" -> result(id, toolResult(JSONObject().put("bodies", JSONArray().put("Body1"))))
            "undo" -> result(id, toolResult(JSONObject().put("undone", "Box1")))
            "redo" -> result(id, toolResult(JSONObject().put("redone", "Box1")))
            "screenshot" -> result(id, toolError("headless: no window"))
            "slow" -> {
                Thread.sleep(4_000)
                result(id, toolResult(JSONObject().put("slept", 4_000)))
            }
            "fail" -> result(id, toolError("the engine refused this on purpose"))
            else -> error(id, -32602, "unknown tool " + name)
        }
    }

    private fun writeStl(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        file.parentFile?.mkdirs()
        // One binary STL triangle: 80-byte header, a count of one, then the facet.
        val bytes = ByteArray(84 + 50)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(80, 1)
        file.writeBytes(bytes)
    }

    private fun toolResult(json: JSONObject): JSONObject = JSONObject().put(
        "content",
        JSONArray().put(JSONObject().put("type", "text").put("text", json.toString(2))),
    )

    private fun toolError(message: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    private fun result(id: Any?, value: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value)

    private fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun write(message: JSONObject) {
        synchronized(stdout) {
            stdout.print(message.toString())
            stdout.print("\n")
            stdout.flush()
        }
    }

    companion object {
        /** Entry point for the spawned fake engine: java -cp ... FakeSolidCraftEngine. */
        @JvmStatic
        fun main(args: Array<String>) {
            FakeSolidCraftEngine(args).run()
        }

        /** The JVM that runs this test, and the classpath it runs this class from. */
        fun processBuilder(vararg args: String): ProcessBuilder {
            val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val classpath = System.getProperty("java.class.path")
            return ProcessBuilder(
                listOf(java, "-cp", classpath, FakeSolidCraftEngine::class.java.name) + args,
            ).redirectErrorStream(false)
        }
    }
}
