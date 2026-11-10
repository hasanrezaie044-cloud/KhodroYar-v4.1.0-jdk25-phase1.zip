package com.khodroyar.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.R
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.tr

/**
 * Terms of use shown once, before anything else (before the PIN screen, so it also
 * covers installs that run with the lock off). Accepting stores [TermsOfUse.VERSION];
 * raise the version when the text changes materially and everyone is asked again.
 */
object TermsOfUse {
    const val VERSION = 1
    const val TITLE = "موافقت‌نامه و سلب مسئولیت"

    const val INTRO =
        "با استفاده از اپلیکیشن «خودرویار» تأیید می‌کنید که این متن را به‌طور کامل خوانده و " +
            "پذیرفته‌اید. در غیر این صورت، لطفاً از اپ استفاده نکنید."

    data class Section(val title: String, val body: String)

    val sections: List<Section> = listOf(
        Section(
            "ماهیت اپ",
            "خودرویار صرفاً ابزاری برای ثبت و مدیریت شخصی اطلاعات خودرو، کارکرد، درآمد و " +
                "هزینه‌هاست و جایگزین مشاورهٔ تخصصی (مالی، حقوقی، مالیاتی یا فنی) نیست. " +
                "گزارش‌ها و محاسبات آن مستند رسمی یا مالیاتی به‌شمار نمی‌آیند.",
        ),
        Section(
            "اطلاعات و حریم خصوصی",
            "همهٔ اطلاعات شما فقط روی همین دستگاه ذخیره می‌شود. اپ به اینترنت متصل نمی‌شود و " +
                "هیچ داده‌ای برای توسعه‌دهنده یا شخص ثالث ارسال نمی‌کند. پشتیبان‌ها (دستی یا خودکار) " +
                "و گزارش‌های صادرشده فایل‌هایی عادی و رمزگذاری‌نشده در حافظهٔ گوشی‌اند — پشتیبان " +
                "خودکار در پوشهٔ Download/KhodroYar — و نگهداری و اشتراک‌گذاری آن‌ها با شماست.",
        ),
        Section(
            "مسئولیت‌های شما",
            "• صحت و کامل بودن اطلاعات واردشده (نرخ‌ها، کیلومتر، تاریخ‌ها و مبالغ) و بررسی " +
                "نتایج پیش از هر تصمیم یا استفادهٔ مالی بر عهدهٔ شماست.\n" +
                "• از اطلاعات خود به‌طور منظم پشتیبان بگیرید و آن را در جای امن نگه دارید. در " +
                "صورت حذف اپ، پاک شدن اطلاعات یا گم‌شدن و خرابی دستگاه، بدون پشتیبان امکان " +
                "بازیابی وجود ندارد.\n" +
                "• محافظت از رمز (PIN) و دستگاه خود نیز با شماست.",
        ),
        Section(
            "محاسبات، یادآورها و ثبت خودکار",
            "درآمد، سود، مصرف سوخت، اقساط و موعد تعویض قطعات بر پایهٔ اطلاعاتی که خودتان وارد " +
                "کرده‌اید محاسبه می‌شود و تقریبی است؛ این موارد جایگزین بازدید فنی، دفترچهٔ " +
                "خودرو یا نظر مکانیک نیستند. اعلان‌ها ممکن است به‌دلیل تنظیمات باتری یا " +
                "سیستم‌عامل با تأخیر برسند یا اجرا نشوند، پس فقط به آن‌ها تکیه نکنید. اگر " +
                "«ثبت خودکار» را فعال کنید، سرویس‌ها طبق اطلاعات ذخیره‌شدهٔ شما خودکار ثبت " +
                "می‌شوند و بررسی آن‌ها بر عهدهٔ شماست.",
        ),
        Section(
            "سلب مسئولیت",
            "اپ «همان‌گونه که هست» ارائه می‌شود و هیچ تضمینی دربارهٔ بی‌خطا بودن، دقت کامل یا " +
                "در دسترس بودن دائمی آن وجود ندارد. توسعه‌دهنده، تا حدی که قانون اجازه " +
                "می‌دهد، مسئول هیچ خسارت مستقیم یا غیرمستقیم — از جمله از دست رفتن داده، زیان " +
                "مالی یا اتکا به محاسبات و گزارش‌ها — نیست.",
        ),
        Section(
            "به‌روزرسانی این متن",
            "ممکن است این متن در نسخه‌های بعدی تغییر کند؛ در این صورت پیش از ادامه، دوباره " +
                "برای تأیید نمایش داده می‌شود.",
        ),
        Section(
            "پذیرش",
            "با زدن «می‌پذیرم و ادامه» یا ادامهٔ استفاده، این شرایط را می‌پذیرید و از " +
                "توسعه‌دهنده سلب مسئولیت می‌کنید. اگر موافق نیستید، «نمی‌پذیرم و خروج» را " +
                "بزنید و از اپ استفاده نکنید.",
        ),
    )
}

/** Intro + all sections, coloured by the caller so it works on the dark gate and in a dialog. */
@Composable
private fun TermsText(headingColor: Color, bodyColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(TermsOfUse.INTRO, color = bodyColor, style = MaterialTheme.typography.bodyMedium)
        TermsOfUse.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    section.title,
                    color = headingColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(section.body, color = bodyColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen gate styled like the login screen. The text card takes whatever height
 * is left and scrolls, while the checkbox and both buttons stay pinned below it — so
 * nothing is pushed off-screen at a large system font scale.
 */
@Composable
fun AgreementScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05090B))) {
            Image(
                painter = painterResource(R.drawable.login_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC05090B), Color(0xE605090B), Color(0xF205090B)),
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color(0x33A8D5C2)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Shield, null, tint = Color(0xFFA8D5C2), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    TermsOfUse.TITLE,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "پیش از شروع، لطفاً متن زیر را بخوانید.",
                    color = Color(0xFFB8C8C4),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = Color(0xE6121A1C),
                    shape = FieldShape,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().weight(1f),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                        TermsText(headingColor = Color(0xFFA8D5C2), bodyColor = Color(0xFFD5E0DD))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = agreed,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFA8D5C2),
                                checkmarkColor = Color(0xFF05090B),
                                uncheckedColor = Color(0xFF8FA09D),
                            ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "متن بالا را خوانده‌ام و می‌پذیرم",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onAccept,
                        enabled = agreed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = FieldShape,
                    ) { Text("می‌پذیرم و ادامه") }
                    TextButton(
                        onClick = onDecline,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("نمی‌پذیرم و خروج", color = Color(0xFFE6A8A0)) }
                }
            }
        }
    }
}

/** Settings → About: lets the owner re-read the accepted text at any time. */
@Composable
fun TermsLinkCard() {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { show = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = FieldShape,
    ) {
        Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr(TermsOfUse.TITLE, "Terms & disclaimer"))
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(TermsOfUse.TITLE) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    TermsText(
                        headingColor = MaterialTheme.colorScheme.primary,
                        bodyColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("بستن") } },
        )
    }
}
