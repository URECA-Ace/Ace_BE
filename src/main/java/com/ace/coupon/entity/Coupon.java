package com.ace.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Coupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "coupon_id")
	private Long id;

	@Column(name = "coupon_name", nullable = false, length = 100)
	private String couponName;

	@Column(name = "type", nullable = false, length = 20)
	private String type;

	@Column(name = "value", nullable = false)
	private Long value;

	@Column(name = "valid_hours", nullable = false)
	private Integer validHours;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;
}
