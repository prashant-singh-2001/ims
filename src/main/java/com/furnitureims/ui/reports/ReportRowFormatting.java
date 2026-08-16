package com.furnitureims.ui.reports;

import com.furnitureims.service.ReportService;

/** The one formatting rule shared by every report row/filter combo that shows an
 *  {@link ReportService.AgingBucket}. */
final class ReportRowFormatting {

    private ReportRowFormatting() {
    }

    static String bucketLabel(ReportService.AgingBucket bucket) {
        return switch (bucket) {
            case DAYS_0_30 -> "0-30 days";
            case DAYS_31_60 -> "31-60 days";
            case DAYS_61_90 -> "61-90 days";
            case DAYS_90_PLUS -> "90+ days";
        };
    }
}
