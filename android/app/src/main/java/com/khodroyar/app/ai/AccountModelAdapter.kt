package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest

/** Explicit account-backed connections. No purchasing, SDK retry, deployment or cross-provider fallback. */
class AccountModelAdapter(
    private val mode: AiMode, private val configuration: () -> AiSettings, private val credentials: AiApiKeyReader,
    private val runtime: AIRequestRuntime, private val clock: () -> Long = System::currentTimeMillis,
) : AIModelAdapter {
    init { require(mode in setOf(AiMode.CLAUDE, AiMode.FIREWORKS, AiMode.GPT)) }
    override val providerId = AIProviderRegistry.get(mode).id
    private var cached = emptyList<AIModel>()
    private var responseIds = emptyMap<String, Set<String>>()
    private var checked = 0L
    private var scope = ""
    private val inferenceBase get() = when (mode) {
        AiMode.CLAUDE -> "https://api.anthropic.com/v1"
        AiMode.FIREWORKS -> "https://api.fireworks.ai/inference/v1"
        AiMode.GPT -> "https://api.openai.com/v1"
        else -> error("Unsupported provider")
    }
    private class Rejected(val failure: AiResult.Failure) : Exception("Request rejected")
    private fun account(): Pair<AiSettings, String> {
        val s = configuration()
        if (s.mode != mode || !s.enabled || !s.consented) throw Rejected(AiResult.Failure(AiErrorKind.DISABLED, "اتصال یا رضایت تغییر کرد."))
        AIProviderRegistry.barrier(s, clock())?.let { throw Rejected(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, it)) }
        return s to (credentials.read()?.let { AIProviderRegistry.normalizeKey(mode, it) }
            ?: throw Rejected(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "کلید این اتصال را در تنظیمات وارد کنید.")))
    }
    private fun unchanged(s: AiSettings) {
        if (configuration() != s) throw Rejected(AiResult.Failure(AiErrorKind.DISABLED, "اتصال تغییر کرد؛ پاسخ قبلی کنار گذاشته شد."))
    }
    private fun error(e: Exception): AiResult.Failure = when (e) {
        is Rejected -> e.failure
        is AIRequestRejected -> e.failure
        is OpenRouterReplyFailure -> e.failure
        is SocketTimeoutException -> AiResult.Failure(AiErrorKind.TIMEOUT, "سرویس دیر پاسخ داد؛ می‌توانید دوباره پیام بفرستید.")
        is IOException -> AiResult.Failure(AiErrorKind.OFFLINE, "اتصال برقرار نشد.")
        else -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "پاسخ سرویس کامل نبود؛ ابزاری اجرا نشد.")
    }
    private suspend fun request(s: AiSettings, key: String, url: String, body: JSONObject? = null, event: ((String) -> Unit)? = null): AiHttpResponse {
        unchanged(s)
        val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream") +
            if (mode == AiMode.CLAUDE) mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01") else mapOf("Authorization" to "Bearer $key")
        val req = AiHttpRequest(url, if (body == null) "GET" else "POST", headers, body?.toString())
        require(AIProviderRegistry.permittedEndpoint(req))
        val response = runtime.send(providerId, req, event)
        unchanged(s)
        if (response.status != 200) throw Rejected(ProviderFailurePolicy.response(response))
        return response
    }
    private suspend fun catalogRows(s: AiSettings, key: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var cursor = ""
        do {
            require(seen.add(cursor)) { "Repeated metadata cursor" }
            val url = when (mode) {
                AiMode.CLAUDE -> "$inferenceBase/models?limit=100" + if (cursor.isNotEmpty()) "&after_id=${URLEncoder.encode(cursor, "UTF-8")}" else ""
                AiMode.FIREWORKS -> "https://api.fireworks.ai/v1/accounts/fireworks/models?pageSize=200" + if (cursor.isNotEmpty()) "&pageToken=${URLEncoder.encode(cursor, "UTF-8")}" else ""
                else -> "$inferenceBase/models"
            }
            val root = NativeJson.objectValue(request(s, key, url).body, 4 * 1024 * 1024)
            val rows = root.getJSONArray(if (mode == AiMode.FIREWORKS) "models" else "data")
            for (i in 0 until rows.length()) result += rows.getJSONObject(i)
            cursor = when {
                mode == AiMode.CLAUDE && root.optBoolean("has_more", false) -> root.getString("last_id").also { require(Regex("[A-Za-z0-9_-]{1,240}").matches(it)) }
                mode == AiMode.FIREWORKS -> root.optString("nextPageToken").also { require(it.length <= 4096 && it.none(Char::isISOControl)) }
                else -> ""
            }
        } while (cursor.isNotEmpty())
        return result
    }
    override suspend fun discover(force: Boolean): AIModelDiscovery = try {
        val (s, key) = account()
        if (scope != s.modelAccountScope) invalidate()
        if (!force && AIRequestPolicy.fresh(checked, clock(), AIRequestPolicy.METADATA_TTL_MS)) AIModelDiscovery.Found(cached, checked)
        else {
            val now = clock()
            val result = catalogRows(s, key).mapNotNull { row -> model(row, now) }
            require(result.map { it.key }.distinct().size == result.size)
            unchanged(s)
            cached = result; checked = now; scope = s.modelAccountScope
            val liveIds = result.map { it.key.id }.toSet()
            responseIds = result.associate { model -> model.key.id to if (mode == AiMode.GPT) OpenAIResponseIdentities.forModel(model.key.id, liveIds) else setOf(model.key.id) }
            AIModelDiscovery.Found(result, now)
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { invalidate(); val f = error(e); runtime.annotateFailure(providerId, f.kind); AIModelDiscovery.Failed(f) }

    private fun model(row: JSONObject, now: Long): AIModel? {
        val id = row.getString(if (mode == AiMode.FIREWORKS) "name" else "id")
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_./:-]{0,239}").matches(id) || id.contains("..")) return null
        if (row.optString("lifecycle", "active") != "active") return null
        if (mode == AiMode.GPT && (!id.startsWith("gpt-") || listOf("audio", "realtime", "transcrib", "tts", "image", "search", "codex").any { id.contains(it) })) return null
        if (mode == AiMode.CLAUDE && !id.startsWith("claude-")) return null
        // The official public serverless catalogue supplies capabilities. We never deploy/create billable resources.
        if (mode == AiMode.FIREWORKS && (!id.startsWith("accounts/fireworks/models/") || !row.optBoolean("supportsServerless", false) ||
                row.optString("state") != "READY" || row.optJSONObject("conversationConfig") == null)) return null
        val context = row.optInt("max_input_tokens", row.optInt("contextLength", row.optInt("context_length", 0))).coerceAtLeast(0)
        val maxOutput = row.optInt("max_tokens", 0).takeIf { it > 0 }
        val vision = row.optJSONObject("capabilities")?.optJSONObject("image_input")?.optBoolean("supported", false) == true ||
            row.optBoolean("supportsImageInput", false) ||
            mode == AiMode.GPT && listOf("gpt-4o", "gpt-4.1", "gpt-5").any { id.startsWith(it) }
        val tools = mode != AiMode.FIREWORKS || row.optBoolean("supportsTools", false)
        val capability = AIModelCapability(tools, context, imageInput = vision, maxOutputTokens = maxOutput)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest("$providerId|$id|$capability".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val publisher = when (mode) {
            AiMode.CLAUDE -> "Anthropic"
            AiMode.GPT -> "OpenAI"
            else -> row.optString("huggingFaceUrl").removePrefix("https://huggingface.co/").substringBefore('/').ifBlank { "Fireworks AI" }
        }
        return AIModel(AIModelKey(providerId, id), AiTextSafety.redact(row.optString("display_name", row.optString("displayName", id))).take(150),
            AIProviderRegistry.get(mode).title, publisher, AIModelPricing(null, null, false), capability,
            AIModelAuthorization.AUTHORIZED, now, fingerprint, costEvidence = AICostEvidence.ACCOUNT_QUOTA)
    }
    override suspend fun availability(model: AIModel, force: Boolean): AIModelAvailability = when (val found = discover(force)) {
        is AIModelDiscovery.Failed -> AIModelAvailability(false, clock(), explanation = found.error.message, errorKind = found.error.kind)
        is AIModelDiscovery.Found -> AIModelAvailability(found.models.any { it.key == model.key && it.fingerprint == model.fingerprint }, found.checkedAt,
            explanation = "فهرست رسمی حساب پاسخ داد؛ این نتیجه تضمین سهمیه یا آزمون inference نیست.")
    }
    override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult = try {
        val (s, key) = account()
        when (val found = discover(false)) { is AIModelDiscovery.Failed -> throw Rejected(found.error); else -> Unit }
        val selected = cached.singleOrNull { it.key == model && AIRequestPolicy.eligibleMetadata(it, clock()) }
            ?: throw Rejected(AiResult.Failure(AiErrorKind.MODEL_INVALID, "مدل را از فهرست همین اتصال انتخاب کنید."))
        if (request.messages.any { it.images.isNotEmpty() } && !selected.capability.imageInput)
            throw Rejected(AiResult.Failure(AiErrorKind.MODEL_INVALID, "مدل انتخابی تصویر نمی‌پذیرد؛ مدل تصویری انتخاب کنید."))
        if (request.tools.isNotEmpty() && !selected.capability.toolCalling)
            throw Rejected(AiResult.Failure(AiErrorKind.MODEL_INVALID, "این مدل ابزار نمی‌پذیرد."))
        require(request.messages.none { key in it.content || AiTextSafety.containsCredential(it.content) || it.calls.any { c -> key in c.arguments || AiTextSafety.containsCredential(c.arguments) } })
        val aliases = responseIds[model.id].orEmpty()
        if (mode == AiMode.CLAUDE) {
            // max_tokens is REQUIRED by Messages. The provider's own maximum is not a local output allowance.
            val output = request.maxOutputTokens ?: selected.capability.maxOutputTokens
                ?: throw Rejected(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "ظرفیت خروجی مدل در API اعلام نشد؛ فهرست را تازه کنید."))
            val body = ClaudeWire.request(model.id, request, output)
            val decoder = ClaudeStream(model.id) { text -> unchanged(s); onText(text) }
            val response = request(s, key, "$inferenceBase/messages", body, if (request.stream) ({ event -> unchanged(s); decoder.accept(event) }) else null)
            if (request.stream && response.body.isBlank()) decoder.result() else ClaudeWire.completion(response.body, model.id)
        } else {
            val body = JSONObject().put("model", model.id).put("messages", JSONArray().apply { request.messages.forEach { put(OpenRouterWire.messageJson(it)) } })
                .put("stream", request.stream)
            request.maxOutputTokens?.let { body.put(if (mode == AiMode.GPT) "max_completion_tokens" else "max_tokens", it) }
            if (request.tools.isNotEmpty()) body.put("tools", OpenRouterToolSchemas.tools(request.tools, compact = true)).put("tool_choice", "auto")
            if (request.stream) body.put("stream_options", JSONObject().put("include_usage", true))
            val decoder = OpenRouterStream(model.id, emptyList(), { text -> unchanged(s); onText(text) }, aliases, verifyZeroCost = false)
            val response = request(s, key, "$inferenceBase/chat/completions", body, if (request.stream) ({ event -> unchanged(s); decoder.accept(event) }) else null)
            val reply = if (request.stream && response.body.isBlank()) decoder.result()
                else OpenRouterWire.completion(response.body, model.id, emptyList(), aliases, verifyZeroCost = false)
            reply.copy(model = model.id) // only an explicitly documented same-model response alias is canonicalised.
        }
    } catch (e: CancellationException) { onText(""); throw e }
    catch (e: Exception) { onText(""); val f = error(e); runtime.annotateFailure(providerId, f.kind); FunctionResult.Failed(f) }
    override fun invalidate() { cached = emptyList(); responseIds = emptyMap(); checked = 0; scope = "" }
}

/** Documented alias/snapshot relationships, intersected with this key's real model list, never used as request fallbacks. */
object OpenAIResponseIdentities {
    private val snapshots = mapOf(
        "gpt-4o" to "gpt-4o-2024-08-06", "gpt-4o-mini" to "gpt-4o-mini-2024-07-18",
        "gpt-4.1" to "gpt-4.1-2025-04-14", "gpt-4.1-mini" to "gpt-4.1-mini-2025-04-14", "gpt-4.1-nano" to "gpt-4.1-nano-2025-04-14",
        "gpt-5" to "gpt-5-2025-08-07", "gpt-5-mini" to "gpt-5-mini-2025-08-07", "gpt-5-nano" to "gpt-5-nano-2025-08-07",
        "gpt-5.1" to "gpt-5.1-2025-11-13", "gpt-5.2" to "gpt-5.2-2025-12-11",
        "gpt-5.4-mini" to "gpt-5.4-mini-2026-03-17", "gpt-5.4-nano" to "gpt-5.4-nano-2026-03-17", "gpt-5.5" to "gpt-5.5-2026-04-23",
    )
    fun forModel(selected: String, liveIds: Set<String>): Set<String> = setOf(selected) + listOfNotNull(snapshots[selected]).filter { it in liveIds }
}
