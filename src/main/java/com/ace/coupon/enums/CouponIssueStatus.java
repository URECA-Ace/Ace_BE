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
	
	CANCELED {
		@Override
		public List<CouponIssueStatus> allowedTransitions() { 
			return List.of();
		}
	},
	
	EXPIRED { 
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
