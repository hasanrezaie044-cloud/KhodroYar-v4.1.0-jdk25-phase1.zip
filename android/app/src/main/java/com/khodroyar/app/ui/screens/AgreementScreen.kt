package com.khodroyar.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.CardShape
import com.khodroyar.app.ui.components.IconBadge
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

    GateBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Outlined.Shield, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(TermsOfUse.TITLE, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("پیش از شروع، با آگاهی ادامه دهید", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = CardShape,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().weight(1f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    TermsText(headingColor = MaterialTheme.colorScheme.primary, bodyColor = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it })
                    .heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Text("متن بالا را خوانده‌ام و می‌پذیرم", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onAccept, enabled = agreed, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = FieldShape) {
                    Text("می‌پذیرم و ادامه")
                }
                TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("نمی‌پذیرم و خروج", color = MaterialTheme.colorScheme.error)
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
