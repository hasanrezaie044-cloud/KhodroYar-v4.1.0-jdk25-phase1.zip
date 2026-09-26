package com.khodroyar.app.ui.screens

import androidx.compose.runtime.Composable
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.repo.AppRepository

/**
 * The Services tab.
 *
 * It used to be a searchable list with a type filter, and registration happened in a
 * floating sheet on top of it. Both are gone: the search box duplicated what the
 * calendar already does better, and the floating registration was the cramped,
 * keyboard-fighting UX. The tab is now exactly one thing — a dedicated, full-screen
 * "register a service" form that resets itself after each save so several services can
 * be entered in a row.
 *
 * Browsing, editing and deleting existing services live in the Calendar (pick a day,
 * tap a service) and in Reports, both of which open the same full-screen form.
 */
@Composable
fun ServicesScreen(repository: AppRepository) {
    ServiceFormScreen(
        repository = repository,
        initialDate = Jalali.todayString(),
        editing = null,
        onDone = null,
    )
}
