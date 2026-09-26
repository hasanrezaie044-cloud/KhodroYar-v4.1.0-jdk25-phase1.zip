package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
import kotlinx.coroutines.launch

/**
 * Personnel details — a real full-screen panel, opened by tapping the personnel card on
 * the dashboard, replacing the little popup that used to appear there.
 *
 * It shows exactly what was already saved, and editing writes back to the same row via
 * `AppRepository.updatePersonnel`, so tapping through and saving can never create a
 * duplicate personnel record.
 */
@Composable
fun PersonnelDetailScreen(repository: AppRepository, personnelId: String) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val people by repository.personnel().collectAsState(initial = emptyList())
    val person = remember(people, personnelId) { people.firstOrNull { it.id == personnelId } }

    var editing by remember { mutableStateOf(false) }
    var name by remember(person?.id) { mutableStateOf(person?.name ?: "") }
    var code by remember(person?.id) { mutableStateOf(person?.personnelCode ?: "") }
    var costCenter by remember(person?.id) { mutableStateOf(person?.costCenter ?: "") }
    var phone by remember(person?.id) { mutableStateOf(person?.phone ?: "") }

    if (person == null) {
        EmptyState(
            "این رکورد پرسنلی پیدا نشد",
            "ممکن است حذف شده باشد. از تنظیمات ← پرسنل بررسی کنید.",
            Icons.Outlined.PersonOff,
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .clearFocusOnTap()
            .padding(horizontal = AppDimens.screenPadding)
            .padding(top = AppDimens.gutter, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
    ) {
        GradientCard(brush = heroBrush()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color.White.copy(alpha = 0.18f),
                ) {
                    Icon(
                        Icons.Outlined.Badge, null,
                        tint = Color.White,
                        modifier = Modifier.padding(12.dp).size(26.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        person.name.ifBlank { "بدون نام" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        person.personnelCode.ifBlank { "کد پرسنلی ثبت نشده" },
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }

        FormSection("اطلاعات ثبت‌شده", Icons.Outlined.ContactPage) {
            InfoRow("نام و نام خانوادگی", person.name.ifBlank { "—" })
            InfoRow("کد پرسنلی", person.personnelCode.ifBlank { "—" })
            InfoRow("مرکز هزینه", person.costCenter.ifBlank { "—" })
            InfoRow("تلفن", person.phone.ifBlank { "—" })
        }

        if (!editing) {
            Button(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text("ویرایش اطلاعات")
            }
        } else {
            FormSection("ویرایش اطلاعات", Icons.Outlined.Edit) {
                TextFieldR("نام و نام خانوادگی", name, { name = it })
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("کد پرسنلی", code, { code = it })
                Spacer(Modifier.height(AppDimens.gap))
                DigitsField("مرکز هزینه", costCenter, { costCenter = it }, maxLength = 12, showCounter = false)
                Spacer(Modifier.height(AppDimens.gap))
                DigitsField("تلفن", phone, { phone = it }, maxLength = 11, showCounter = false, imeAction = ImeAction.Done)
                Spacer(Modifier.height(AppDimens.gutter))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (name.isBlank()) { toast.show("نام را وارد کنید"); return@Button }
                            scope.launch {
                                // Update in place — never an insert, so no duplicate row.
                                repository.updatePersonnel(person.id, name.trim(), code.trim(), costCenter.trim(), phone.trim())
                                editing = false
                                toast.show("اطلاعات پرسنلی به‌روزرسانی شد")
                            }
                        },
                        modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) { Text("ذخیره تغییرات") }
                    OutlinedButton(
                        onClick = {
                            name = person.name; code = person.personnelCode
                            costCenter = person.costCenter; phone = person.phone
                            editing = false
                        },
                        modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) { Text(tr("انصراف", "Cancel")) }
                }
            }
        }

        Text(
            "این اطلاعات فقط روی همین دستگاه ذخیره می‌شود.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
