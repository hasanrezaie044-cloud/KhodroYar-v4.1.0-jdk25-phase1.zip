package com.khodroyar.app.ai

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** All OpenRouter discovery, guardrail filtering and endpoint-specific metadata lives here. */
object OpenRouterModelMetadata {
    const val PROVIDER = "openrouter"
    const val USER_MODELS_URL = "${OpenRouterFreePolicy.BASE_URL}/models/user"
    fun validId(id: String) = id.length <= 240 && Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.:-]*").matches(id) && !id.contains("..")
    fun endpointUrl(id: String): String { require(validId(id)); return "${OpenRouterFreePolicy.MODELS_URL}/$id/endpoints" }
    fun prices(value: JSONObject?): AIModelPricing {
        fun decimal(key: String): BigDecimal? = value?.opt(key)?.takeIf { it is Number || it is String }?.toString()
            ?.takeIf { it.length <= 100 }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        val allZero = value != null && value.length() in 2..64 && value.keys().asSequence().all { decimal(it)?.signum() == 0 }
        return AIModelPricing(decimal("prompt"), decimal("completion"), allZero)
    }
    private fun rows(body: String): Map<String, JSONObject> {
        val array = NativeJson.objectValue(body, 4 * 1024 * 1024).getJSONArray("data")
        require(array.length() <= 10000)
        val result = linkedMapOf<String, JSONObject>()
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i); val id = row.opt("id") as? String ?: continue
            require(!result.containsKey(id)) { "Duplicate catalog identity" }
            if (validId(id)) result[id] = row
        }
        return result
    }
    private fun parameters(row: JSONObject): Set<String> = row.optJSONArray("supported_parameters")?.let { a ->
        (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet()
    }.orEmpty()
    private fun context(row: JSONObject): Int = runCatching { BigDecimal(row.get("context_length").toString()).intValueExact() }.getOrDefault(0)
    private fun retired(row: JSONObject, now: Long): Boolean {
        if (row.optBoolean("deprecated", false)) return true
        return listOf("expiration_date", "deprecation_date").any { field ->
            if (!row.has(field) || row.isNull(field)) false else runCatching {
                LocalDate.parse(row.getString(field).take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() <= now
            }.getOrDefault(true)
        }
    }
    fun responseIdentities(row: JSONObject): Set<String> = setOfNotNull(row.opt("id") as? String,
        (row.opt("canonical_slug") as? String)?.takeIf(::validId))
    fun parse(publicBody: String, authorizedBody: String, now: Long): Pair<List<AIModel>, Map<String, Set<String>>> {
        val public = rows(publicBody); val user = rows(authorizedBody)
        val identities = mutableMapOf<String, Set<String>>()
        val result = public.mapNotNull { (id, row) ->
            val price = prices(row.optJSONObject("pricing"))
            // Keep blocked/unknown free variants visible, without displaying hundreds of irrelevant paid rows.
            if (!price.verifiedFree && !id.endsWith(":free") && id != OpenRouterFreePolicy.INITIAL_MODEL) return@mapNotNull null
            val allowed = user[id]
            val supports = parameters(row)
            val authorized = allowed != null && prices(allowed.optJSONObject("pricing")).verifiedFree &&
                parameters(allowed).containsAll(setOf("tools", "tool_choice", "max_tokens")) && !retired(allowed, now) &&
                responseIdentities(allowed) == responseIdentities(row)
            val textOutput = row.optJSONObject("architecture")?.optJSONArray("output_modalities")?.let { a ->
                (0 until a.length()).any { a.opt(it) == "text" }
            } ?: true // This fixed endpoint defaults to text-output models, not the all-modalities catalog.
            val capabilities = AIModelCapability(supports.containsAll(setOf("tools", "tool_choice", "max_tokens")), context(row), textOutput)
            val canonical = (row.opt("canonical_slug") as? String).orEmpty()
            val fingerprintText = listOf(id, canonical, capabilities.toString(), supports.sorted().joinToString(",")).joinToString("|")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(fingerprintText.toByteArray()).joinToString("") { "%02x".format(it) }
            identities[id] = responseIdentities(row)
            AIModel(AIModelKey(PROVIDER, id), AiTextSafety.redact(row.optString("name", id)).take(150), "OpenRouter", id.substringBefore('/'),
                price, capabilities, if (authorized) AIModelAuthorization.AUTHORIZED else AIModelAuthorization.NOT_AUTHORIZED,
                now, fingerprint, pinnedIdentity = id != OpenRouterFreePolicy.INITIAL_MODEL, deprecated = retired(row, now))
        }
        require(result.size <= 500)
        return result to identities
    }
    fun availability(body: String, model: AIModel, identities: Set<String>, now: Long): AIModelAvailability {
        val data = NativeJson.objectValue(body, 4 * 1024 * 1024).getJSONObject("data")
        require((data.opt("id") as? String)?.let { it in identities } == true)
        val endpoints = data.getJSONArray("endpoints"); require(endpoints.length() <= 256)
        val active = (0 until endpoints.length()).map { endpoints.getJSONObject(it) }.filter { endpoint ->
            val status = endpoint.opt("status")
            val online = status is Number && runCatching { BigDecimal(status.toString()).intValueExact() == 0 }.getOrDefault(false)
            online && prices(endpoint.optJSONObject("pricing")).verifiedFree && context(endpoint) >= 32768 &&
                parameters(endpoint).containsAll(setOf("tools", "tool_choice"))
        }
        val providers = active.map { AiTextSafety.redact(it.optString("provider_name", "ارائه‌دهنده")).take(80) }.distinct().take(12)
        val uptime = active.mapNotNull { row -> (row.opt("uptime_last_30m") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 } }.maxOrNull() ?: 0.0
        return AIModelAvailability(active.isNotEmpty(), now, providers, uptime,
            if (active.isEmpty()) "هیچ endpoint فعال، ابزارپذیر و با همهٔ قیمت‌های صفر تأیید نشد" else "ارائه‌دهندهٔ فعال و قیمت صفر تأیید شد")
    }
}

/** Only this adapter knows about /models/user, canonical slugs, endpoints and OpenRouter's wire. */
class OpenRouterModelAdapter(
    private val client: OpenRouterClient, private val clock: () -> Long = System::currentTimeMillis,
) : AIModelAdapter {
    override val providerId = OpenRouterModelMetadata.PROVIDER
    private var catalog: AIModelDiscovery.Found? = null
    private var epoch = AIModelCatalogEpoch.value.get()
    private var identities = emptyMap<String, Set<String>>()
    private val health = mutableMapOf<AIModelKey, AIModelAvailability>()
    override fun invalidate() { catalog = null; identities = emptyMap(); health.clear() }
    override suspend fun discover(force: Boolean): AIModelDiscovery {
        val currentEpoch = AIModelCatalogEpoch.value.get()
        if (epoch != currentEpoch) { invalidate(); epoch = currentEpoch }
        if (!force) catalog?.takeIf { AIRequestPolicy.fresh(it.checkedAt, clock(), AIRequestPolicy.METADATA_TTL_MS) }?.let { return it }
        val public = client.metadata(OpenRouterFreePolicy.MODELS_URL, false)
        if (public is OpenRouterMetadataResult.Failed) { invalidate(); return AIModelDiscovery.Failed(public.error) }
        val user = client.metadata(OpenRouterModelMetadata.USER_MODELS_URL, true)
        if (user is OpenRouterMetadataResult.Failed) { invalidate(); return AIModelDiscovery.Failed(user.error) }
        return try {
            val (models, aliases) = OpenRouterModelMetadata.parse((public as OpenRouterMetadataResult.Body).value, (user as OpenRouterMetadataResult.Body).value, clock())
            identities = aliases
            AIModelDiscovery.Found(models, clock()).also { catalog = it }
        } catch (_: Exception) {
            invalidate(); AIModelDiscovery.Failed(AiResult.Failure(AiErrorKind.MODEL_INVALID, "فهرست رسمی مدل/قیمت/دسترسی قابل تأیید نیست؛ از فهرست قدیمی یا مدل پولی استفاده نمی‌شود."))
        }
    }
    override suspend fun availability(model: AIModel, force: Boolean): AIModelAvailability {
        if (!force) health[model.key]?.takeIf { AIRequestPolicy.fresh(it.checkedAt, clock(), AIRequestPolicy.METADATA_TTL_MS) }?.let { return it }
        if (!AIRequestPolicy.eligibleMetadata(model, clock())) return AIModelAvailability(false, clock(), explanation = "قیمت/هویت/مجوز مدل معتبر نیست")
        val result = client.metadata(OpenRouterModelMetadata.endpointUrl(model.key.id), false)
        val value = when (result) {
            is OpenRouterMetadataResult.Failed -> AIModelAvailability(false, clock(), explanation = result.error.message)
            is OpenRouterMetadataResult.Body -> runCatching {
                OpenRouterModelMetadata.availability(result.value, model, identities[model.key.id].orEmpty(), clock())
            }.getOrElse { AIModelAvailability(false, clock(), explanation = "پاسخ وضعیت ارائه‌دهنده قابل تأیید نیست") }
        }
        health[model.key] = value; return value
    }
    override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult {
        val found = discover(false)
        if (found is AIModelDiscovery.Failed) return FunctionResult.Failed(found.error)
        val verified = (found as AIModelDiscovery.Found).models.singleOrNull { it.key == model && AIRequestPolicy.eligibleMetadata(it, clock()) }
            ?: return FunctionResult.Failed(AiResult.Failure(AiErrorKind.MODEL_INVALID, "مدل در فهرست تازهٔ قیمت صفر و مجاز برای کلید تأیید نشد؛ درخواست ارسال نشد."))
        val available = availability(verified)
        if (!available.available) return FunctionResult.Failed(AiResult.Failure(AiErrorKind.NOT_FOUND, available.explanation))
        return client.inferApproved(verified, available, identities[model.id].orEmpty(), request, onText)
    }
}

/** A background discovery invalidates all in-process metadata caches before their next inference. */
internal object AIModelCatalogEpoch { val value = java.util.concurrent.atomic.AtomicLong() }
