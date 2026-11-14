package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException

/** Direct OpenAI-compatible connection, matching the user's GapGPT Python example on the wire. */
class DirectGapGptProvider(
    private val configuration: () -> AiSettings,
    private val credentials: AiApiKeyReader,
    private val transport: AiHttpTransport = UrlConnectionAiTransport(),
) : AIProvider {
    override val name: String = "GapGPT — direct Chat Completions"

    override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult =
        withContext(Dispatchers.IO) {
            val settings = configuration()
            // Check before reading the key or making ANY request, including connection tests.
            if (!settings.enabled || settings.mode != AiMode.GAPGPT_DIRECT || !settings.consented) {
                return@withContext AiResult.Failure(AiErrorKind.DISABLED, "اتصال مستقیم GapGPT فعال نیست یا رضایت ارسال اطلاعات داده نشده است.")
            }
            if (GapGptDirectContract.normalizeBaseUrl(settings.gapGptBaseUrl) == null) {
                return@withContext AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "برای اتصال مستقیم فقط آدرس اصلی GapGPT یا CDN رسمی معرفی‌شده را انتخاب کنید.")
            }
            val key = try {
                credentials.read()?.let(GapGptDirectContract::normalizeApiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "کلید رمزنگاری‌شده قابل خواندن نیست؛ کلید را دوباره در تنظیمات وارد کنید. هیچ کلیدی در متن ساده ذخیره نمی‌شود.")
            } ?: return@withContext AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "کلید واقعی حساب GapGPT وارد نشده است. YOUR_GAPGPT_API_KEY فقط جای‌نگهدارِ نمونه است. کلید را فقط در تنظیمات برنامه وارد کنید، نه در چت.")
            val wanted = model?.trim()?.ifBlank { null } ?: GapGptDirectContract.selectedModel(settings)
            try {
                val request = GapGptDirectContract.chatRequest(settings.gapGptBaseUrl, key, messages, wanted, maxTokens ?: AiLimits.MAX_OUTPUT_TOKENS)
                GapGptDirectContract.chatResponse(transport.execute(request), wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SocketTimeoutException) {
                AiResult.Failure(AiErrorKind.TIMEOUT, "پاسخی از GapGPT در زمان تعیین‌شده دریافت نشد.")
            } catch (_: IOException) {
                AiResult.Failure(AiErrorKind.OFFLINE, "ارتباط HTTPS با GapGPT برقرار نشد. اینترنت یا آدرس اصلی/CDN انتخابی را بررسی کنید.")
            } catch (_: Exception) {
                AiResult.Failure(AiErrorKind.BAD_REQUEST, "ساخت یا ارسال درخواست مستقیم GapGPT انجام نشد. تنظیمات مدل را بررسی کنید.")
            }
        }

    /**
     * Explicit, potentially billable probe, not a health/status URL invented for GapGPT.
     * Only called after the user presses Test and accepts consent. No history/app data is sent.
     */
    override suspend fun status(): AiProviderStatus {
        val settings = configuration()
        val result = chat(listOf(AiMessage("user", "سلام! فقط یک پاسخ کوتاه بده.")), GapGptDirectContract.selectedModel(settings), 32)
        return when (result) {
            is AiResult.Success -> AiProviderStatus(
                reachable = true, enabled = true, configured = true,
                defaultModel = result.model, modelAvailable = true,
                message = "پاسخ واقعی Chat Completions از GapGPT دریافت شد؛ مدل پاسخ‌دهنده: ${result.model.ifBlank { GapGptDirectContract.selectedModel(settings) }}.",
            )
            is AiResult.Failure -> AiProviderStatus(
                reachable = result.kind !in setOf(AiErrorKind.OFFLINE, AiErrorKind.TIMEOUT, AiErrorKind.NOT_CONFIGURED, AiErrorKind.DISABLED),
                message = result.message, errorKind = result.kind,
            )
        }
    }
}
