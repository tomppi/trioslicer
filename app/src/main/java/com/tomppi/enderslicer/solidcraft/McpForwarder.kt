package com.tomppi.enderslicer.solidcraft

import org.json.JSONObject

/**
 * The engine session's raw MCP request surface.
 *
 * [SolidCraftSession] implements this over the stdio client; tests implement it with a lambda,
 * and the unified engine MCP server forwards external tools/list and tools/call onto it - so the
 * token gate, error mapping and change reporting are exercised without a process.
 */
interface McpForwarder {
    fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject
    fun notify(method: String, params: JSONObject)
}
