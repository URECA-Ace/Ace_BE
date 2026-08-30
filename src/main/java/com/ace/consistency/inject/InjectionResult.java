package com.ace.consistency.inject;

/** 위반 주입 1건의 결과. 관리 화면에 그대로 보여줄 수 있도록 사람이 읽을 수 있는 설명을 담는다. */
public record InjectionResult(String checkName, Long eventId, String message) {
}
