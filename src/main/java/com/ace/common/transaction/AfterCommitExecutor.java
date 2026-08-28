package com.ace.common.transaction;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 트랜잭션이 커밋된 뒤에만 외부 부수 효과를 실행한다. */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AfterCommitExecutor {

	public static void execute(Runnable action) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			runSafely(action);
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			log.warn("활성 트랜잭션의 synchronization이 비활성 상태여서 afterCommit 작업을 건너뜁니다.");
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				runSafely(action);
			}
		});
	}

	private static void runSafely(Runnable action) {
		try {
			action.run();
		} catch (RuntimeException exception) {
			log.warn("afterCommit 작업 실행에 실패했습니다.", exception);
		}
	}
}
