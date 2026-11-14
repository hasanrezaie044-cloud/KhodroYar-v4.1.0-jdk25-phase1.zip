package com.khodroyar.app.data.repo

import com.khodroyar.app.data.db.ServiceEntity
import org.junit.Assert.*
import org.junit.Test

class QuickDraftTest {
    private val today="1405/08/20"
    @Test fun missingAmountOrTitleNeverBecomesAnExpense() { assertNotNull(QuickDraft(date=today,title="قهوه").error(today));assertNotNull(QuickDraft(date=today,amount=100.0).error(today)) }
    @Test fun validExpenseIsOnlyAProposal() { assertNull(QuickDraft(date=today,title="قهوه",amount=50_000.0,category="coffee").error(today)) }
    @Test fun nanNegativeAndExcessiveAmountsAreRejected() { listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,1_000_000_001.0).forEach { assertNotNull(QuickDraft(date=today,title="قهوه",amount=it).error(today)) } }
    @Test fun invalidAndFutureDatesAreRejected() { for(d in listOf("1405/13/01","1405/08/21","1405/8/20")) assertNotNull(QuickDraft(date=d,title="قهوه",amount=50.0).error(today)) }
    @Test fun fuelNeedsRealLitresAndKnownType() { assertNotNull(QuickDraft(QuickKind.FUEL,today,liters=0.0).error(today));assertNotNull(QuickDraft(QuickKind.FUEL,today,liters=50.0,fuelType="unknown").error(today));assertNull(QuickDraft(QuickKind.FUEL,today,liters=50.0,amount=1000.0).error(today)) }
    @Test fun unsupportedMaintenanceIsNotConvertedToOther() { assertNotNull(QuickDraft(QuickKind.MAINTENANCE,today,maintenanceType="magic").error(today)) }
    @Test fun tripCopyDoesNotShareReceiptRequestNumberOrId() {
        val row=ServiceEntity("original",today,"night","soren",80.0,3.5,3.5,"08:00","11:30","مبارکه","اصفهان","نام","123",1,0.0,0.0,0.0,1_000_000L,"old","/private/receipt.jpg")
        val copy=QuickDraft.from(row,today)
        assertEquals("",copy.trip!!.id);assertEquals("",copy.trip.requestNumber);assertEquals("",copy.trip.attachmentPath);assertEquals("نام",copy.trip.passengers);assertNull(copy.error(today))
    }
    @Test fun fixedSalaryNeedsNoInventedDistanceOrRoute() {
        val row=ServiceEntity("salary",today,"fixed-salary","soren",0.0,0.0,0.0,"","","","","","",0,0.0,0.0,0.0,2_000_000L,"")
        assertNull(QuickDraft.from(row,today).error(today))
        assertNotNull(QuickDraft.from(row.copy(type="night"),today).error(today))
        assertNotNull(QuickDraft.from(row.copy(workHours=Double.NaN),today).error(today))
    }
    @Test fun emptyTripCannotBeSubmitted() { assertNotNull(QuickDraft(QuickKind.SERVICE,today).error(today)) }
}
