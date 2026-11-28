package kr.tx24.wx.api;

/** 모계좌에 등록한 대외 연동 기관이다. */
public enum Acquirer {

    HYPHEN, COOCON, DOZN, UNKNOWN;

    public static Acquirer from(String acq) {

        return switch (acq) {
            case "HYPHEN" -> HYPHEN;
            case "COOCON" -> COOCON;
            // 기존 관리자 화면은 DOZN을 DOZM으로 저장한다.
            case "DOZN", "DOZM" -> DOZN;
            default -> UNKNOWN;
        };
    }

    public boolean isInactive() {

        return this == COOCON || this == DOZN;
    }
}
