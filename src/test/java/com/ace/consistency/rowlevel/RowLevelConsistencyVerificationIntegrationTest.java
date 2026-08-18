package com.ace.consistency.rowlevel;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.coupon.repository.CouponEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RowLevelConsistencyVerificationIntegrationTest {

	@Autowired
	private RowLevelConsistencyVerificationService verificationService;

	@Autowired
	private VerificationResultRepository resultRepository;

	@Autowired
	private CouponEventRepository eventRepository;

	@Test
	void 구조_검증은_공통_Runner를_통해_실행되고_결과가_저장된다() {
		Long eventId = eventRepository.findAll(PageRequest.of(0, 1))
				.getContent().getFirst().getId();
		long beforeCount = resultRepository.count();

		List<VerificationResult> results = verificationService.verifyStructural(
				Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results).hasSize(2);
		assertThat(results).allMatch(VerificationResult::isPass);
		assertThat(resultRepository.count()).isEqualTo(beforeCount + results.size());
	}
}
