package com.furnitureims.util;

import java.time.LocalDate;

/** FR-SYS-04: the financial year runs 1 April to 31 March, formatted "26-27". Shared by
 *  every document series that resets yearly - debit notes, invoice numbers, credit notes. */
public final class FinancialYear {

    private FinancialYear() {
    }

    public static String current() {
        return of(LocalDate.now());
    }

    public static String of(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        int endYear = (startYear + 1) % 100;
        return String.format("%02d-%02d", startYear % 100, endYear);
    }
}
