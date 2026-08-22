package com.piecetrack.domain;

/**
 * The official CBIC GST state code table, used everywhere a state must resolve to the
 * 2-digit code that decides CGST/SGST vs IGST (FR-SAL-05) and validates a GSTIN's prefix
 * (FR-PUR-02). Source: CBIC GST state code list, verified 15 Aug 2026.
 */
public enum IndianState {

    JAMMU_AND_KASHMIR("Jammu and Kashmir", "01"),
    HIMACHAL_PRADESH("Himachal Pradesh", "02"),
    PUNJAB("Punjab", "03"),
    CHANDIGARH("Chandigarh", "04"),
    UTTARAKHAND("Uttarakhand", "05"),
    HARYANA("Haryana", "06"),
    DELHI("Delhi", "07"),
    RAJASTHAN("Rajasthan", "08"),
    UTTAR_PRADESH("Uttar Pradesh", "09"),
    BIHAR("Bihar", "10"),
    SIKKIM("Sikkim", "11"),
    ARUNACHAL_PRADESH("Arunachal Pradesh", "12"),
    NAGALAND("Nagaland", "13"),
    MANIPUR("Manipur", "14"),
    MIZORAM("Mizoram", "15"),
    TRIPURA("Tripura", "16"),
    MEGHALAYA("Meghalaya", "17"),
    ASSAM("Assam", "18"),
    WEST_BENGAL("West Bengal", "19"),
    JHARKHAND("Jharkhand", "20"),
    ODISHA("Odisha", "21"),
    CHHATTISGARH("Chhattisgarh", "22"),
    MADHYA_PRADESH("Madhya Pradesh", "23"),
    GUJARAT("Gujarat", "24"),
    DADRA_NAGAR_HAVELI_DAMAN_DIU("Dadra and Nagar Haveli and Daman and Diu", "26"),
    MAHARASHTRA("Maharashtra", "27"),
    KARNATAKA("Karnataka", "29"),
    GOA("Goa", "30"),
    LAKSHADWEEP("Lakshadweep", "31"),
    KERALA("Kerala", "32"),
    TAMIL_NADU("Tamil Nadu", "33"),
    PUDUCHERRY("Puducherry", "34"),
    ANDAMAN_AND_NICOBAR("Andaman and Nicobar Islands", "35"),
    TELANGANA("Telangana", "36"),
    ANDHRA_PRADESH("Andhra Pradesh", "37"),
    LADAKH("Ladakh", "38"),
    OTHER_TERRITORY("Other Territory", "97"),
    OTHER_COUNTRY("Other Country", "99");

    private final String displayName;
    private final String gstCode;

    IndianState(String displayName, String gstCode) {
        this.displayName = displayName;
        this.gstCode = gstCode;
    }

    public String displayName() {
        return displayName;
    }

    public String gstCode() {
        return gstCode;
    }

    public static IndianState byGstCode(String code) {
        for (IndianState state : values()) {
            if (state.gstCode.equals(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown GST state code: " + code);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
