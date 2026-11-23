package com.khodroyar.app.data.repo

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khodroyar.app.core.finance.LoanCalculator
import com.khodroyar.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 5.9.1 regression audit of loan installment editing, run against the real Room database.
 * The confirmed defects: editing a loan's title wiped every per-installment edit and re-keyed
 * all rows, and free-text paid dates were stored as "paid".
 */
@RunWith(AndroidJUnit4::class)
class LoanInstallmentEditIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private lateinit var repository: AppRepository

    @Before fun reset(): Unit = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "This integration test clears only its disposable emulator database."
        }
        repository = AppRepository(context)
        db.clearAllTables()
    }

    @After fun cleanup(): Unit = runBlocking { db.clearAllTables() }

    private suspend fun rows(loanId: String) = db.loanInstallments().forLoanOnce(loanId)

    @Test fun titleOnlyEditKeepsEveryInstallmentEditAndId() = runBlocking {
        val id = repository.addLoan("وام آزمایشی", 12_000_000.0, 0.0, "1405/01/01", 12)
        val before = rows(id)
        assertEquals(12, before.size)
        val third = before.first { it.number == 3 }
        // Persian digits are accepted and stored canonically.
        repository.updateLoanInstallment(third.copy(amount = 1_500_000L, paidDate = "۱۴۰۵/۰۳/۰۱", notes = "پرداخت دستی"))

        repository.updateLoan(id, "وام ویرایش‌شده", 12_000_000.0, 0.0, "1405/01/01", 12, 0.0, "یادداشت جدید")

        val after = rows(id)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals((1..12).toList(), after.map { it.number })
        val edited = after.first { it.number == 3 }
        assertEquals(1_500_000L, edited.amount)
        assertEquals("1405/03/01", edited.paidDate)
        assertEquals("پرداخت دستی", edited.notes)
        val loan = db.loans().byId(id)!!
        assertEquals("وام ویرایش‌شده", loan.title)
        assertTrue(loan.paidInstallments.split(',').contains("3"))
    }

    @Test fun termsChangeRegeneratesAmountsButKeepsPaidRowsIdsAndNotes() = runBlocking {
        val id = repository.addLoan("وام", 12_000_000.0, 0.0, "1405/01/01", 12)
        val before = rows(id)
        repository.toggleInstallment(id, 2)
        val paidDate = rows(id).first { it.number == 2 }.paidDate
        assertTrue(paidDate.isNotBlank())
        repository.updateLoanInstallment(rows(id).first { it.number == 4 }.copy(notes = "یادداشت قسط ۴"))

        repository.updateLoan(id, "وام", 10_000_000.0, 0.0, "1405/01/01", 10, 0.0, "")

        val after = rows(id)
        assertEquals((1..10).toList(), after.map { it.number })
        assertEquals("no duplicate ids", after.size, after.map { it.id }.toSet().size)
        assertEquals(before.filter { it.number <= 10 }.map { it.id }, after.map { it.id })
        assertEquals(paidDate, after.first { it.number == 2 }.paidDate)
        assertEquals("یادداشت قسط ۴", after.first { it.number == 4 }.notes)
        assertTrue(after.filter { it.number != 2 }.all { it.paidDate.isBlank() })
        assertEquals(LoanCalculator.totalWithInterest(10_000_000.0, 0.0), after.sumOf { it.amount })
    }

    @Test fun invalidDatesAreRejectedAndLeaveTheRowUntouched() = runBlocking {
        val id = repository.addLoan("وام", 6_000_000.0, 0.0, "1405/01/01", 6)
        val row = rows(id).first { it.number == 1 }
        for (bad in listOf(row.copy(paidDate = "abc"), row.copy(dueDate = "1405/13/40"), row.copy(paidDate = "1405/02/32"))) {
            try {
                repository.updateLoanInstallment(bad)
                fail("accepted invalid installment $bad")
            } catch (expected: IllegalArgumentException) {
                // rejected as intended
            }
        }
        assertEquals(row, rows(id).first { it.number == 1 })
        assertFalse(db.loans().byId(id)!!.paidInstallments.split(',').contains("1"))

        // Clearing a paid date marks the installment unpaid again in both places.
        repository.updateLoanInstallment(row.copy(paidDate = "1405/02/01"))
        assertTrue(db.loans().byId(id)!!.paidInstallments.split(',').contains("1"))
        repository.updateLoanInstallment(rows(id).first { it.number == 1 }.copy(paidDate = "  "))
        assertEquals("", rows(id).first { it.number == 1 }.paidDate)
        assertFalse(db.loans().byId(id)!!.paidInstallments.split(',').contains("1"))
    }
}
