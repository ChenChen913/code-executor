package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.language.LanguageSpec;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import com.chenchen.codeexecutor.model.ExecutionStatus;
import com.chenchen.codeexecutor.model.PhaseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 演练模式：不创建任何容器，只把"将会下发给 Docker 的命令"渲染出来。
 *
 * <p>存在的意义有两个：
 * <ol>
 *   <li>本机没装 / 没启动 Docker 时，整个 HTTP 流程照样能跑通、能看懂</li>
 *   <li>对不熟悉 Docker 的人，把加固参数一条条列出来，比看文档直观得多</li>
 * </ol>
 *
 * <p>开启方式：{@code executor.dry-run=true}，或启动时加 {@code --executor.dry-run=true}。
 */
@Component
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "true")
public class DryRunCodeRunner implements CodeRunner {

    private static final Logger log = LoggerFactory.getLogger(DryRunCodeRunner.class);

    private final SandboxPolicy sandbox;

    public DryRunCodeRunner(SandboxPolicy sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public ExecutionResponse run(String jobId, LanguageSpec spec, SourceBundle source) {
        String plan = sandbox.describeRunPlan(jobId, spec, source);
        log.info("演练模式（未创建容器），等价的 docker 操作：\n{}", plan);

        // 把计划放在 stdout 里返回，这样用 curl 就能直接看到
        PhaseOutcome run = PhaseOutcome.finished(0, plan, "", false);
        return ExecutionResponse.of(jobId, spec.getId(), ExecutionStatus.DRY_RUN, null, run,
                0L, "演练模式：已渲染命令，未真正执行");
    }
}
