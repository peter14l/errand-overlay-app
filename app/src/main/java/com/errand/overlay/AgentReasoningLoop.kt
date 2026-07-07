package com.errand.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AgentReasoningLoop(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onStateChanged(state: String, pillText: String)
        fun onError(error: String)
        fun onTaskComplete()
    }

    private var callback: Callback? = null
    private var isRunning = false

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun startTask(userRequest: String, geminiApiKey: String) {
        if (isRunning) return
        isRunning = true
        Thread {
            runLoop(userRequest, geminiApiKey)
        }.start()
    }

    fun stopTask() {
        isRunning = false
    }

    private fun runLoop(userRequest: String, apiKey: String) {
        var step = 1
        val history = mutableListOf<String>()
        history.add("User request: $userRequest")

        while (isRunning) {
            val service = ErrandAccessibilityService.getInstance()
            if (service == null) {
                mainHandler.post { callback?.onError("Accessibility Service not connected. Enable it in Settings.") }
                isRunning = false
                break
            }

            // Get window hierarchy
            val nodes = service.getWindowHierarchy()
            if (nodes.isEmpty()) {
                mainHandler.post { callback?.onStateChanged("Thinking", "Analyzing screen (Empty)...") }
                Thread.sleep(2000)
                continue
            }

            // Formulate prompt
            val screenRepresentation = StringBuilder()
            nodes.forEachIndexed { index, node ->
                if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
                    screenRepresentation.append(
                        "Index: $index, ID: ${node.viewIdResourceName}, Text: '${node.text ?: ""}', Desc: '${node.contentDescription ?: ""}', Clickable: ${node.isClickable}, Editable: ${node.isEditable}, Bounds: [${node.left}, ${node.top}, ${node.right}, ${node.bottom}]\n"
                    )
                }
            }

            mainHandler.post { callback?.onStateChanged("Thinking", "Reasoning step $step...") }

            val prompt = """
You are an Android Task Agent automation system. Your current goal is to satisfy the user's request: "$userRequest".
Here is the current screen content:
$screenRepresentation

Here is the task history so far:
${history.joinToString("\n")}

Decide the next action to perform. You must respond with a JSON object containing:
1. "thought": A clear statement of what you see and what you need to do next.
2. "action": One of:
   - "click" (requires "index" field containing the node index)
   - "type" (requires "index" field and "text" field)
   - "scroll_down" (no index required)
   - "scroll_up" (no index required)
   - "handoff_payment" (stops for manual payment completion)
   - "complete" (task successful)
   - "fail" (reasoning failed)
3. "pillText": A short string (2-4 words) describing what you are doing (e.g., "Adding items...", "Searching Zomato...").

Your response must be JSON only. No markdown formatting.
""".trimIndent()

            try {
                val nextAction = queryGemini(prompt, apiKey) ?: break
                val thought = nextAction.get("thought")?.asString ?: ""
                val action = nextAction.get("action")?.asString ?: "fail"
                val pillText = nextAction.get("pillText")?.asString ?: "Thinking..."

                history.add("Step $step: Thought: $thought | Action: $action")

                if (action == "fail") {
                    mainHandler.post { callback?.onError("Agent failed: $thought") }
                    isRunning = false
                    break
                }

                if (action == "handoff_payment") {
                    mainHandler.post { callback?.onStateChanged("PaymentHandoff", "Please complete payment manually") }
                    isRunning = false
                    break
                }

                if (action == "complete") {
                    mainHandler.post { callback?.onStateChanged("Complete", "Task completed!") }
                    Thread.sleep(1500)
                    mainHandler.post { callback?.onTaskComplete() }
                    isRunning = false
                    break
                }

                mainHandler.post { callback?.onStateChanged("Acting", pillText) }

                // Perform the action
                var success = false
                when (action) {
                    "click" -> {
                        val index = nextAction.get("index")?.asInt ?: -1
                        if (index in nodes.indices) {
                            success = service.performClick(nodes[index].node)
                        }
                    }
                    "type" -> {
                        val index = nextAction.get("index")?.asInt ?: -1
                        val text = nextAction.get("text")?.asString ?: ""
                        if (index in nodes.indices) {
                            success = service.performClick(nodes[index].node)
                            Thread.sleep(500)
                            success = service.performTypeText(nodes[index].node, text)
                        }
                    }
                    "scroll_down" -> {
                        success = service.performScrollDown()
                    }
                    "scroll_up" -> {
                        success = service.performScrollUp()
                    }
                }

                history.add("Action Success: $success")
                step++
                Thread.sleep(2000) // Delay between steps

            } catch (e: Exception) {
                Log.e("AgentReasoningLoop", "Error during reasoning", e)
                mainHandler.post { callback?.onError("Error: ${e.message}") }
                isRunning = false
                break
            }
        }
    }

    private fun queryGemini(prompt: String, apiKey: String): JsonObject? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val jsonRequest = JsonObject().apply {
            val contents = JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", prompt)
                        })
                    })
                })
            }
            add("contents", contents)
            add("generationConfig", JsonObject().apply {
                addProperty("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(jsonRequest).toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP code: $response")
            }
            val body = response.body?.string() ?: throw IOException("Empty response body")
            val jsonObject = gson.fromJson(body, JsonObject::class.java)
            val candidates = jsonObject.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val candidate = candidates.get(0).asJsonObject
                val content = candidate.getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    val text = parts.get(0).asJsonObject.get("text").asString
                    return gson.fromJson(text, JsonObject::class.java)
                }
            }
            return null
        }
    }
}
