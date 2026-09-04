package com.crosschecklab.global.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// 분석 실행 전용 비동기 스레드 풀
// 요청 스레드의 traceId(MDC)를 작업 스레드로 복사해 로그 추적을 잇는다.
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String ANALYSIS_EXECUTOR = "analysisTaskExecutor";

    // 큐까지 가득 차면 CallerRunsPolicy 로 호출 스레드에서 실행 (202 로 수락된 작업은 유실시키면 안 됨)
    @Bean(name = ANALYSIS_EXECUTOR)
    public Executor analysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("analysis-");
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    // 복구 스캔은 단일 스레드에서 직렬 실행해 같은 프로세스 안에서 중복 스캔이 겹치지 않게 한다.
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("analysis-recovery-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    // 호출한 요청 스레드의 MDC(traceId 등)를 작업 스레드에 복사
    private TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
