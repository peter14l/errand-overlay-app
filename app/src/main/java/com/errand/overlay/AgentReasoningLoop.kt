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
            "in.swiggy.android" to "Swiggy",
            "com.Swiggy.agent" to "Swiggy",
            "in.swiggy.agent" to "Swiggy",
            "com.Swiggy" to "Swiggy",
            "com.application.zomato" to "Zomato",
            "com.zomato.agent" to "Zomato",
            "com.zomato" to "Zomato",
            "com.zeptonow.android" to "Zepto",
            "com.zepto" to "Zepto",
            "net.grofers.customer" to "Blinkit",
            "com.blinkit" to "Blinkit"
        )

        // Reverse map: friendly name → package names (for launching variants)
        private val APP_PACKAGES = mapOf(
            "Swiggy" to listOf("in.swiggy.android", "in.swiggy.agent", "com.Swiggy.agent", "com.Swiggy"),
            "Zomato" to listOf("com.application.zomato", "com.zomato.agent", "com.zomato"),
            "Zepto" to listOf("com.zeptonow.android", "com.zepto"),
            "Blinkit" to listOf("net.grofers.customer", "com.blinkit")
        )

        // Keywords in user request → target app
        private val REQUEST_APP_HINTS = mapOf(
            "swiggy" to "Swiggy",
            "zomato" to "Zomato",
            "zepto" to "Zepto",
            "blinkit" to "Blinkit",
            "pizza" to "Swiggy",
            "burger" to "Swiggy",
            "food" to "Swiggy",
            "restaurant" to "Swiggy",
            "grocery" to "Zepto",
            "groceries" to "Zepto"
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
        fun onAskUser(question: String)
    }

    private var callback: Callback? = null
    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    private val pauseLock = Object()
    @Volatile private var userAnswer: String? = null

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

    fun answerUser(answer: String) {
        userAnswer = answer
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
            // Auto-navigate to target app if not already there
            val service = ErrandAccessibilityService.getInstance()
            if (service != null) {
                val targetApp = detectTargetApp(userRequest)
                val currentPkg = service.getForegroundApp()
                val currentApp = KNOWN_APPS[currentPkg] ?: ""

                if (targetApp != null && currentApp != targetApp) {
                    mainHandler.post { callback?.onStateChanged("Thinking", "Opening $targetApp...") }
                    service.performHome()
                    Thread.sleep(800)
                    val pkgs = APP_PACKAGES[targetApp] ?: emptyList()
                    var launched = false
                    for (pkg in pkgs) {
                        if (service.launchApp(pkg)) {
                            launched = true
                            break
                        }
                    }
                    if (launched) {
                        Thread.sleep(3000) // wait for app to load
                    }
                }
            }

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

                val nextAction = queryGemini(prompt, apiKey)
                if (nextAction == null) {
                    mainHandler.post { callback?.onError("Gemini returned empty response. Check API key and network.") }
                    break
                }
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

                if (action == "ask_user") {
                    nodes.recycleAll()
                    val question = nextAction.get("question")?.asString ?: "I need your input."
                    isPaused = true
                    userAnswer = null
                    mainHandler.post { callback?.onAskUser(question) }
                    // Wait for user to answer
                    synchronized(pauseLock) {
                        while (isPaused && isRunning) {
                            pauseLock.wait()
                        }
                    }
                    if (!isRunning) break
                    // Add user's answer to history so LLM sees it
                    val answer = userAnswer ?: "No answer provided"
                    history.add("User answer: $answer")
                    userAnswer = null
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
                    "home" -> {
                        success = service.performHome()
                    }
                    "launch_app" -> {
                        val pkg = nextAction.get("package")?.asString ?: ""
                        val appName = KNOWN_APPS[pkg]
                        if (appName != null) {
                            val pkgs = APP_PACKAGES[appName] ?: listOf(pkg)
                            var launched = false
                            for (p in pkgs) {
                                if (service.launchApp(p)) {
                                    launched = true
                                    break
                                }
                            }
                            success = launched
                        } else {
                            success = service.launchApp(pkg)
                        }
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

    private fun detectTargetApp(userRequest: String): String? {
        val lower = userRequest.lowercase()
        // Check explicit app names first
        for ((keyword, appName) in REQUEST_APP_HINTS) {
            if (lower.contains(keyword)) return appName
        }
        return null
    }

    private fun buildPrompt(
        userRequest: String,
        appName: String,
        domainHint: String,
        screenText: String,
        history: List<String>
    ): String {
        val preferences = extractPreferences(userRequest)
        return """
You are Errand, an Android task automation agent. You interact with apps by reading their accessibility tree and performing actions.

**Current app:** $appName
**User's goal:** "$userRequest"
**User preferences:** $preferences

**Domain context:** $domainHint

**Current screen elements:**
$screenText

**Task history:**
${history.joinToString("\n")}

**Your job:** Decide the NEXT SINGLE ACTION to progress toward the user's goal.

**NAVIGATION RULE — THIS IS CRITICAL:**
If the current app is NOT the target app for the user's request, you MUST navigate there first:
1. Use "home" to go to the Android home screen.
2. Then use "launch_app" with the correct package name to open the target app.
3. Wait for it to load, then proceed with the task.

Known package names: Swiggy → com.Swiggy, Zomato → com.zomato, Zepto → com.zepto, Blinkit → com.blinkit

Do NOT try to interact with UI elements that don't exist on the current screen. If you're on the home screen or the wrong app, navigate first.

**SEARCH FLOW (for food/grocery apps):**
1. Find the search bar (look for elements with text like "Search", "Search for food", "Search restaurants", or editable fields near the top).
2. Click the search bar (use "click" action).
3. Wait one step, then type the item name (use "type" action with the search term, e.g. "pizza", "biryani", "milk").
4. Wait for results to load, then browse and select.

**SELECTION RULES (when multiple results appear):**
- If the user mentioned a specific restaurant name (e.g. "from Domino's"), find and click that one.
- If the user said "cheapest" or "budget", pick the option with the lowest visible price.
- If the user said "best" or "highest rated", pick the option with the highest rating (e.g. 4.5★ > 4.2★).
- If the user said "fastest" or "nearest", pick the option with the lowest delivery time.
- If no preference is stated, pick the FIRST result (the app's default ranking is usually most relevant).
- Do NOT scroll past the first 3-4 options unless none match. Quick decisions are better.

**Available actions:**
- "click" — Tap a UI element. Required: "index" (node index from screen).
- "type" — Enter text into an editable field. Required: "index" (editable node), "text".
- "scroll_down" — Scroll down the page. No extra params.
- "scroll_up" — Scroll up the page. No extra params.
- "back" — Press the Android back button. No extra params.
- "home" — Go to the Android home screen. No extra params.
- "launch_app" — Open an app by package name. Required: "package" (e.g. "com.Swiggy").
- "long_click" — Long-press a UI element. Required: "index".
- "swipe" — Swipe gesture. Required: "direction" (one of: "left", "right", "up", "down"). Optional: "index" (element to swipe on).
- "handoff_payment" — Ask user to complete payment manually. Use when you reach a payment screen.
- "complete" — Task is successfully done. Use when the final goal is achieved.
- "fail" — Task cannot be completed. Include reason in "thought".
- "ask_user" — Ask the user a clarifying question. Required: "question". Use when you're genuinely stuck and need user input (e.g. "Which restaurant?"). Pause and wait for response.

**Rules:**
1. Always pick the SINGLE next action. Do not bundle multiple actions.
2. Use element indices from the screen elements list.
3. For typing, always click the field first in a SEPARATE step, then type in the next step.
4. If an action failed, try a different approach or element.
5. When the order is placed / item is in cart and you reach payment, use "handoff_payment".
6. Use "complete" only when the ENTIRE user request is fulfilled.
7. ALWAYS navigate to the correct app before attempting any task-specific actions.
8. Be decisive. Pick a restaurant quickly based on the selection rules above. Do not overthink.
9. Only use "ask_user" as a LAST RESORT after you've tried everything else.

Respond with JSON only. No markdown.
{
  "thought": "What you observe and your plan",
  "action": "action_name",
  "index": 0,
  "text": "",
  "direction": "",
  "package": "",
  "question": "",
  "pillText": "Short status (2-4 words)"
}
""".trimIndent()
    }

    private fun extractPreferences(userRequest: String): String {
        val lower = userRequest.lowercase()
        val prefs = mutableListOf<String>()

        if (lower.contains("cheapest") || lower.contains("budget") || lower.contains("low price"))
            prefs.add("price-sensitive: pick the cheapest option")
        if (lower.contains("best") || lower.contains("highest rated") || lower.contains("top rated"))
            prefs.add("quality-focused: pick the highest rated option")
        if (lower.contains("fastest") || lower.contains("quick") || lower.contains("nearest"))
            prefs.add("speed-focused: pick the fastest delivery option")
        if (lower.contains("vegetarian") || lower.contains("veg "))
            prefs.add("vegetarian only")
        if (lower.contains("non-veg") || lower.contains("non veg") || lower.contains("chicken") || lower.contains("meat"))
            prefs.add("non-vegetarian preferred")

        // Check for specific restaurant names
        val restaurantNames = listOf("domino", "pizza hut", "mcdonald", "kfc", "subway", "starbucks",
            "burger king", "chaayos", "faasos", "behrouz", "oven story", "sweet truth", "theobroma")
        for (name in restaurantNames) {
            if (lower.contains(name)) {
                prefs.add("specific restaurant: $name")
                break
            }
        }

        return if (prefs.isEmpty()) "none stated — use app's default ranking" else prefs.joinToString("; ")
    }

    private fun queryGemini(prompt: String, apiKey: String): JsonObject? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
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
