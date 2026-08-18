package com.ace.consistency.rowlevel;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.rowlevel.check.CouponExpirationLagConsistencyCheck;
import com.ace.consistency.rowlevel.check.CouponHistoryStructuralConsistencyCheck;
import com.ace.consistency.rowlevel.check.CouponIssueHistoryStateConsistencyCheck;
import com.ace.consistency.rowlevel.check.CouponIssueStructuralConsistencyCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 트리거가 실행 시점에 맞는 Check만 골라 Runner에 전달할 수 있도록 그룹을 제공한다. */
@Component
@RequiredArgsConstructor
public class RowLevelConsistencyCheckGroup {

	private final CouponIssueStructuralConsistencyCheck issueStructuralCheck;
	private final CouponHistoryStructuralConsistencyCheck historyStructuralCheck;
	private final CouponIssueHistoryStateConsistencyCheck historyStateCheck;
	private final CouponExpirationLagConsistencyCheck expirationLagCheck;

	public List<ConsistencyCheck> structuralChecks() {
		return List.of(issueStructuralCheck, historyStructuralCheck);
	}

	public List<ConsistencyCheck> stateTransitionChecks() {
		return List.of(historyStateCheck);
	}

	public List<ConsistencyCheck> expirationChecks() {
		return List.of(expirationLagCheck);
	}
}
