package com.ace.consistency.repository;

import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * VerificationResult 영속화 담당.
 * 실제 구현은 verification_result 테이블에 매핑하는 JPA/MyBatis Repository로 작성한다.
 * Runner는 이 인터페이스에만 의존하므로, 저장소 구현을 바꾸더라도 Runner나 Check 코드는
 * 건드릴 필요가 없다.
 */
@Repository
public interface VerificationResultRepository extends JpaRepository<VerificationResultEntity, Long> {
}
