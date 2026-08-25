package com.ace.coupon.enums;

import java.util.List;

public enum CouponIssueStatus {
	ISSUED { 
		@Override
		public List<CouponIssueStatus> allowedTransitions() {
			return List.of(USED, EXPIRED);
		}
	},
	
	USED {
		@Override
		public List<CouponIssueStatus> allowedTransitions() {  
			return List.of(ISSUED); 
		}
	},
	
	EXPIRED { 
		@Override  
		public List<CouponIssueStatus> allowedTransitions() {
			return List.of(); 
		}
	},
	
	/**
	 * 비즈니스 로직 상 도달 불가 상태 / DB에 레코드가 존재하여 유지함
	 * JPA 런타임 매핑 에러 방지용/ 추후 정합팀 논의 후 삭제 필요.
	 */
	CANCELED {
		@Override
		public List<CouponIssueStatus> allowedTransitions() {
			return List.of();
		}
	};

	public abstract List<CouponIssueStatus> allowedTransitions();

	public boolean canTransitTo(CouponIssueStatus target) {
		return allowedTransitions().contains(target);
	}
}
