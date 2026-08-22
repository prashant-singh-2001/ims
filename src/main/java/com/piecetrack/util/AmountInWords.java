package com.piecetrack.util;

import com.piecetrack.money.Money;

/**
 * Converts a rupee amount into words using Indian numbering (lakh, crore) - FR-DOC-01's
 * mandatory "grand total in words" field on the printed invoice.
 */
public final class AmountInWords {

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
            "Seventeen", "Eighteen", "Nineteen"
    };
    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private AmountInWords() {
    }

    public static String toWords(Money amount) {
        long paisaTotal = Math.abs(amount.paisa());
        long rupees = paisaTotal / 100;
        long paise = paisaTotal % 100;

        StringBuilder sb = new StringBuilder("Rupees ");
        sb.append(rupees == 0 ? "Zero" : rupeesToWords(rupees));
        if (paise > 0) {
            sb.append(" and ").append(twoDigitWords((int) paise)).append(" Paise");
        }
        sb.append(" Only");
        return sb.toString();
    }

    private static String rupeesToWords(long n) {
        long crore = n / 1_00_00_000L;
        n %= 1_00_00_000L;
        long lakh = n / 1_00_000L;
        n %= 1_00_000L;
        long thousand = n / 1_000L;
        n %= 1_000L;
        long hundred = n / 100L;
        long remainder = n % 100L;

        StringBuilder sb = new StringBuilder();
        if (crore > 0) {
            sb.append(rupeesToWords(crore)).append(" Crore ");
        }
        if (lakh > 0) {
            sb.append(twoDigitWords((int) lakh)).append(" Lakh ");
        }
        if (thousand > 0) {
            sb.append(twoDigitWords((int) thousand)).append(" Thousand ");
        }
        if (hundred > 0) {
            sb.append(ONES[(int) hundred]).append(" Hundred ");
        }
        if (remainder > 0) {
            if (hundred > 0) {
                sb.append("and ");
            }
            sb.append(twoDigitWords((int) remainder)).append(' ');
        }
        return sb.toString().trim();
    }

    private static String twoDigitWords(int n) {
        if (n < 20) {
            return ONES[n];
        }
        int tens = n / 10;
        int ones = n % 10;
        return ones == 0 ? TENS[tens] : TENS[tens] + "-" + ONES[ones];
    }
}
