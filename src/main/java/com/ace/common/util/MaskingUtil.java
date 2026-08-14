package com.ace.common.util;

import java.util.regex.Pattern;

// 개인정보 마스킹 유틸
// 대상: email / name / phone
public final class MaskingUtil {

	// tester@test.com -> tes****@test.com
	private static final Pattern EMAIL = Pattern.compile("([\\w.+-]{1,3})[\\w.+-]*(@[\\w.-]+\\.[A-Za-z]{2,})");

	// 012-7577-2823 -> 012-****-2823
	private static final Pattern PHONE = Pattern.compile("\\b(0\\d{1,2})[-.\\s]?(\\d{3,4})[-.\\s]?(\\d{4})\\b");

	private MaskingUtil() {
	}

	public static String mask(String text) {
		if (text == null || text.isBlank()) {
			return text;
		}
		String masked = EMAIL.matcher(text).replaceAll("$1****$2");
		return PHONE.matcher(masked).replaceAll("$1-****-$3");
	}

	public static String maskEmail(String email) {
		if (email == null || email.isBlank()) {
			return email;
		}
		return EMAIL.matcher(email).replaceAll("$1****$2");
	}

	public static String maskPhone(String phone) {
		if (phone == null || phone.isBlank()) {
			return phone;
		}
		return PHONE.matcher(phone).replaceAll("$1-****-$3");
	}

	// 홍길동 -> 홍*동 / 김철 -> 김* / 이 -> 이
	public static String maskName(String name) {
		if (name == null || name.isBlank()) {
			return name;
		}
		String trimmed = name.trim();
		int length = trimmed.length();

		if (length == 1) {
			return trimmed;
		}
		if (length == 2) {
			return trimmed.charAt(0) + "*";
		}
		return trimmed.charAt(0) + "*".repeat(length - 2) + trimmed.charAt(length - 1);
	}
}
