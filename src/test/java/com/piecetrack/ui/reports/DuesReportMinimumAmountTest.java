package com.piecetrack.ui.reports;

import com.piecetrack.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The "Minimum amount" filter on the dues report (FR-RPT-04) is the one free-text money
 * field on that screen, so it is the one place a stray keystroke reaches {@link Money}
 * directly. These cover the three ways that used to go wrong: text that is not a number,
 * a negative floor (meaningless against balances that {@code ReportService} has already
 * filtered down to positives, so it silently behaved as no filter at all), and a value
 * past what the paisa {@code long} inside {@code Money.ofRupees} can hold - which threw
 * ArithmeticException, not NumberFormatException, and so escaped the FXML handler
 * uncaught.
 */
class DuesReportMinimumAmountTest {

    @Test
    void blankInputMeansNoMinimum() {
        assertNull(DuesReportController.parseOptionalMoney(null));
        assertNull(DuesReportController.parseOptionalMoney(""));
        assertNull(DuesReportController.parseOptionalMoney("   "));
    }

    @Test
    void ordinaryAmountsParseAndZeroIsAllowed() {
        assertEquals(Money.ofPaisa(50_000), DuesReportController.parseOptionalMoney("500"));
        assertEquals(Money.ofPaisa(50_050), DuesReportController.parseOptionalMoney("  500.50  "));
        assertEquals(Money.ZERO, DuesReportController.parseOptionalMoney("0"));
    }

    @Test
    void nonNumericInputIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DuesReportController.parseOptionalMoney("abc"));
        assertEquals("Please enter a valid minimum amount.", e.getMessage());
    }

    @Test
    void negativeMinimumIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DuesReportController.parseOptionalMoney("-1"));
        assertEquals("Minimum amount cannot be negative.", e.getMessage());
    }

    @Test
    void amountBeyondMoneysRangeIsRejectedInsteadOfOverflowing() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DuesReportController.parseOptionalMoney("99999999999999999999"));
        assertEquals("Minimum amount is too large.", e.getMessage());
    }

    /** The largest value that still fits, to prove the bound is not off by a paisa. */
    @Test
    void largestRepresentableAmountIsStillAccepted() {
        assertEquals(Money.ofPaisa(Long.MAX_VALUE),
                DuesReportController.parseOptionalMoney("92233720368547758.07"));
    }

    /**
     * Scientific notation lets a short string name an astronomically large number. The
     * check has to reject it by comparison, before {@code setScale} tries to materialise
     * two billion digits - hence the timeout: hanging here is as much a failure as
     * returning the wrong answer.
     */
    @Test
    @Timeout(10)
    void hugeExponentIsRejectedPromptly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DuesReportController.parseOptionalMoney("1E+2000000000"));
        assertEquals("Minimum amount is too large.", e.getMessage());
    }
}
