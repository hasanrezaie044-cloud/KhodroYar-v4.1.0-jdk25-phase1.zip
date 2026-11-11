package com.khodroyar.app.data.repo

import kotlinx.coroutines.sync.Mutex

/** One in-process gate for manual, quick and scheduled record mutations. Not a replacement for Room transactions. */
internal object RecordMutations { val mutex = Mutex() }
