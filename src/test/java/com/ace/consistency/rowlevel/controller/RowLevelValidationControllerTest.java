package com.ace.consistency.rowlevel.controller;

import com.ace.consistency.rowlevel.domain.ValidationStatus;
import com.ace.consistency.rowlevel.dto.CouponIssueRow;
import com.ace.consistency.rowlevel.dto.RowValidationRequest;
import com.ace.consistency.rowlevel.dto.RowValidationResponse;
import com.ace.consistency.rowlevel.service.CouponIssueBatchValidationService;
import com.ace.consistency.rowlevel.service.RowLevelValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowLevelValidationControllerTest {

	private final RowLevelValidationService validationService = mock(RowLevelValidationService.class);
	private final RowLevelValidationController controller = new RowLevelValidationController(
			validationService, mock(CouponIssueBatchValidationService.class));

	@Test
	void 성공_결과를_공통_ApiResponse로_반환한다() {
		LocalDateTime snapshotAt = LocalDateTime.of(2026, 8, 14, 10, 0);
		CouponIssueRow row = new CouponIssueRow(
				1L, 1L, 1L, 1,
				"11111111-1111-1111-1111-111111111111", null,
				"ISSUED", snapshotAt.minusHours(1), snapshotAt.minusHours(1),
				snapshotAt.plusHours(23), null, null, snapshotAt.minusHours(1));
		RowValidationResponse result = new RowValidationResponse(
				"coupon_issue", "1", snapshotAt, ValidationStatus.PASS, 1, 0, 0, List.of());
		when(validationService.validateIssue(row, snapshotAt)).thenReturn(result);

		var response = controller.validateIssue(new RowValidationRequest<>(snapshotAt, row));

		assertThat(response.result()).isEqualTo("success");
		assertThat(response.data()).isSameAs(result);
		assertThat(response.error()).isNull();
	}

	@Test
	void 검증_기준시각이_없으면_400_예외를_던진다() {
		CouponIssueRow row = mock(CouponIssueRow.class);

		assertThatThrownBy(() -> controller.validateIssue(new RowValidationRequest<>(null, row)))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
						.isEqualTo(400));
	}
}
