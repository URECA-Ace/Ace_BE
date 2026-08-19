package com.ace.coupon.repository;

import com.ace.coupon.entity.IssueFailureLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IssueFailureLogRepository extends JpaRepository<IssueFailureLog, Long> {

	List<IssueFailureLog> findAllByRequestId(String requestId);
}
