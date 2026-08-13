package com.ace.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("개인정보 마스킹")
class MaskingUtilTest {

	@DisplayName("이메일은 로컬파트 앞 3글자만 남긴다")
	@ParameterizedTest
	@CsvSource({
			"tester@test.com,     tes****@test.com",
			"user1@test.com,      use****@test.com",
			"ab@x.co,             ab****@x.co"
	})
	void maskEmail(String raw, String expected) {
		assertThat(MaskingUtil.maskEmail(raw)).isEqualTo(expected);
	}

	@DisplayName("전화번호는 가운데 자리를 가린다")
	@ParameterizedTest
	@CsvSource({
			"010-1234-5678, 010-****-5678",
			"012-7577-2823, 012-****-2823",
			"01012345678,   010-****-5678"
	})
	void maskPhone(String raw, String expected) {
		assertThat(MaskingUtil.maskPhone(raw)).isEqualTo(expected);
	}

	@DisplayName("이름은 첫 글자와 끝 글자만 남긴다")
	@ParameterizedTest
	@CsvSource({
			"홍길동,   홍*동",
			"남궁민수, 남**수",
			"김철,     김*",
			"이,       이"
	})
	void maskName(String raw, String expected) {
		assertThat(MaskingUtil.maskName(raw)).isEqualTo(expected);
	}

	@Test
	@DisplayName("자유 문자열에 섞인 이메일과 전화번호를 함께 마스킹한다")
	void maskMixedText() {
		String raw = "발급 실패 user123456@test.com / 012-235-5524 처리 불가";

		assertThat(MaskingUtil.mask(raw))
				.isEqualTo("발급 실패 use****@test.com / 012-****-5524 처리 불가");
	}

	@DisplayName("개인정보가 아닌 식별자는 마스킹하지 않는다")
	@ParameterizedTest
	@ValueSource(strings = {
			"issue_id=1234567890 event_id=12 seq=4821",
			"eventId=12:4821 messageId=02b1dc0b-686b-4543-a1b6-9c1333402ad3",
			"재고가 모두 소진되었습니다."
	})
	void doesNotMaskIdentifiers(String raw) {
		assertThat(MaskingUtil.mask(raw)).isEqualTo(raw);
	}

	@DisplayName("null 과 공백은 그대로 반환한다")
	@Test
	void nullAndBlank() {
		assertThat(MaskingUtil.mask(null)).isNull();
		assertThat(MaskingUtil.mask("  ")).isEqualTo("  ");
		assertThat(MaskingUtil.maskEmail(null)).isNull();
		assertThat(MaskingUtil.maskPhone(null)).isNull();
		assertThat(MaskingUtil.maskName(null)).isNull();
	}
}
