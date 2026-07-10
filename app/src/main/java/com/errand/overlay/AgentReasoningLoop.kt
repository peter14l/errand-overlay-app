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

    companion object {
        private const val MAX_STEPS = 30
        private const val MAX_HISTORY_ENTRIES = 20
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val KNOWN_APPS = mapOf(
            "com.Swiggy.agent" to "Swiggy",
            "in.swiggy.agent" to "Swiggy",
            "com.zomato.agent" to "Zomato",
            "com.zomato" to "Zomato",
            "com.zepto" to "Zepto",
            "com.blinkit" to "Blinkit"
        )

        private val DOMAIN_HINTS = mapOf(
            "Swiggy" to "You are on Swiggy (food delivery). Typical flow: search restaurant → browse menu → add items to cart → apply coupons → proceed to checkout → select payment → handoff to user for payment.",
            "Zomato" to "You are on Zomato (food delivery). Typical flow: search restaurant → browse menu → add items to cart → apply coupons → proceed to checkout → select payment → handoff to user for payment.",
            "Zepto" to "You are on Zepto / Blinkit (quick commerce / grocery). Typical flow: search item → add to cart → apply coupons → proceed to checkout → select payment → handoff to user for payment.",
            "Blinkit" to "You are on Blinkit (quick commerce / grocery). Typical flow: search item → add to cart → apply coupons → proceed to checkout → select payment → handoff to user for payment."
        )
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onStateChanged(state: String, pillText: String)
        fun onError(error: String)
        fun onTaskComplete()
        fun onPaymentHandoff(pillText: String)
    }

    private var callback: Callback? = null
    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    private val pauseLock = Object()

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun startTask(userRequest: String, geminiApiKey: String) {
        if (isRunning) return
        isRunning = true
        isPaused = false
        Thread {
            runLoop(userRequest, geminiApiKey)
        }.start()
    }

    fun stopTask() {
        isRunning = false
        isPaused = false
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
    }

    fun resumeAfterPayment() {
        isPaused = false
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
    }

    private fun runLoop(userRequest: String, apiKey: String) {
        var step = 1
        val history = mutableListOf<String>()
        history.add("User request: $userRequest")

        try {
            while (isRunning) {
                if (step > MAX_STEPS) {
                    mainHandler.post { callback?.onError("Reached max steps ($MAX_STEPS). Task may be stuck.") }
                    break
                }

                val service = ErrandAccessibilityService.getInstance()
                if (service == null) {
                    mainHandler.post { callback?.onError("Accessibility Service not connected. Enable it in Settings.") }
                    break
                }

                // Detect foreground app
                val foregroundPkg = service.getForegroundApp()
                val appName = KNOWN_APPS[foregroundPkg] ?: foregroundPkg.ifEmpty { "Unknown" }

                // Get window hierarchy
                val nodes = service.getWindowHierarchy()
                if (nodes.isEmpty()) {
                    mainHandler.post { callback?.onStateChanged("Thinking", "Analyzing screen (Empty)...") }
                    Thread.sleep(2000)
                    continue
                }

                // Build screen representation
                val screenRepresentation = StringBuilder()
                nodes.forEachIndexed { index, node ->
                    if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
                        screenRepresentation.append(
                            "[$index] ${node.className.substringAfterLast('.')}" +
                            " | Text: '${node.text ?: ""}'" +
                            " | Desc: '${node.contentDescription ?: ""}'" +
                            " | Clickable: ${node.isClickable} Editable: ${node.isEditable} LongClickable: ${node.isLongClickable}" +
                            " | Bounds: [${node.left},${node.top},${node.right},${node.bottom}]" +
                            " | ID: ${node.viewIdResourceName}\n"
                        )
                    }
                }

                val screenText = screenRepresentation.toString()
                if (screenText.isBlank()) {
                    mainHandler.post { callback?.onStateChanged("Thinking", "No visible elements...") }
                    Thread.sleep(2000)
                    continue
                }

                mainHandler.post { callback?.onStateChanged("Thinking", "Reasoning step $step...") }

                val domainHint = DOMAIN_HINTS[appName] ?: "You are on app: $appName. Explore the UI to complete the task."
                val prompt = buildPrompt(userRequest, appName, domainHint, screenText, history)

                val nextAction = queryGemini(prompt, apiKey) ?: break
                val thought = nextAction.get("thought")?.asString ?: ""
                val action = nextAction.get("action")?.asString ?: "fail"
                val pillText = nextAction.get("pillText")?.asString ?: "Thinking..."

                history.add("Step $step [$appName]: $thought → $action")
                if (history.size > MAX_HISTORY_ENTRIES) {
                    history.removeAt(0)
                }

                if (action == "fail") {
                    nodes.recycleAll()
                    mainHandler.post { callback?.onError("Agent failed: $thought") }
                    break
                }

                if (action == "complete") {
                    nodes.recycleAll()
                    mainHandler.post { callback?.onStateChanged("Complete", "Task completed!") }
                    Thread.sleep(1500)
                    mainHandler.post { callback?.onTaskComplete() }
                    break
                }

                if (action == "handoff_payment") {
                    nodes.recycleAll()
                    isPaused = true
                    mainHandler.post { callback?.onPaymentHandoff(pillText) }
                    // Wait for user to complete payment
                    synchronized(pauseLock) {
                        while (isPaused && isRunning) {
                            pauseLock.wait()
                        }
                    }
                    if (!isRunning) break
                    // Resume — fetch fresh screen after payment
                    step++
                    continue
                }

                mainHandler.post { callback?.onStateChanged("Acting", pillText) }

                // Re-fetch hierarchy after LLM call
                val freshNodes = service.getWindowHierarchy()
                nodes.recycleAll()

                var success = false
                when (action) {
                    "click" -> {
                        val index = nextAction.get("index")?.asInt ?: -1
                        if (index in freshNodes.indices) {
                            success = service.performClick(freshNodes[index].node)
                        }
                    }
                    "type" -> {
                        val index = nextAction.get("index")?.asInt ?: -1
                        val text = nextAction.get("text")?.asString ?: ""
                        if (index in freshNodes.indices) {
                            service.performClick(freshNodes[index].node)
                            Thread.sleep(500)
                            success = service.performTypeText(freshNodes[index].node, text)
                        }
                    }
                    "scroll_down" -> {
                        success = service.performScrollDown()
                    }
                    "scroll_up" -> {
                        success = service.performScrollUp()
                    }
                    "back" -> {
                        success = service.performBack()
                    }
                    "long_click" -> {
                        val index = nextAction.get("index")?.asInt ?: -1
                        if (index in freshNodes.indices) {
                            success = service.performLongClick(freshNodes[index].node)
                        }
                    }
                    "swipe" -> {
                        val direction = nextAction.get("direction")?.asString ?: "left"
                        val index = nextAction.get("index")?.asInt
                        success = service.performSwipeDirection(direction, index, freshNodes)
                    }
                }

                freshNodes.recycleAll()
                history.add("Action: $action → Success: $success")
                step++
                Thread.sleep(2000)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e("AgentReasoningLoop", "Error during reasoning", e)
            mainHandler.post { callback?.onError("Error: ${e.message}") }
        } finally {
            isRunning = false
            isPaused = false
        }
    }

    private fun buildPrompt(
        userRequest: String,
        appName: String,
        domainHint: String,
        screenText: String,
        history: List<String>
    ): String {
        return """
You are Errand, an Android task automation agent. You interact with apps by reading their accessibility tree and performing actions.

**Current app:** $appName
**User's goal:** "$userRequest"

**Domain context:** $domainHint

**Current screen elements:**
$screenText

**Task history:**
${history.joinToString("\n")}

**Your job:** Decide the NEXT SINGLE ACTION to progress toward the user's goal.

**Available actions:**
- "click" — Tap a UI element. Required: "index" (node index from screen).
- "type" — Enter text into an editable field. Required: "index" (editable node), "text".
- "scroll_down" — Scroll down the page. No extra params.
- "scroll_up" — Scroll up the page. No extra params.
- "back" — Press the Android back button. No extra params.
- "long_click" — Long-press a UI element. Required: "index".
- "swipe" — Swipe gesture. Required: "direction" (one of: "left", "right", "up", "down"). Optional: "index" (element to swipe on).
- "handoff_payment" — Ask user to complete payment manually. Use when you reach a payment screen.
- "complete" — Task is successfully done. Use when the final goal is achieved.
- "fail" — Task cannot be completed. Include reason in "thought".

**Rules:**
1. Always pick the SINGLE next action. Do not bundle multiple actions.
2. Use element indices from the screen elements list.
3. For typing, always click the field first in a SEPARATE step, then type in the next step.
4. If an action failed, try a different approach or element.
5. When the order is placed / item is in cart and you reach payment, use "handoff_payment".
6. Use "complete" only when the ENTIRE user request is fulfilled.

Respond with JSON only. No markdown.
{
  "thought": "What you observe and your plan",
  "action": "action_name",
  "index": 0,
  "text": "",
  "direction": "",
  "pillText": "Short status (2-4 words)"
}
""".trimIndent()
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
                addProperty("temperature", 0.2)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(jsonRequest).toRequestBody(mediaType))
            .build()

        sharedClient.newCall(request).execute().use { response ->
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
