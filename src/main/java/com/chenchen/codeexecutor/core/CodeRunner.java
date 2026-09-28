package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.language.LanguageSpec;
import com.chenchen.codeexecutor.model.ExecutionResponse;

/**
 * 真正"把代码跑起来"的那个组件。
 *
 * <p>之所以抽成接口，是因为有两个实现：
 * <ul>
 *   <li>{@link DockerCodeRunner} —— 真跑（需要 Docker）</li>
 *   <li>{@link DryRunCodeRunner} —— 只把将要下发的命令渲染出来（不需要 Docker）</li>
 * </ul>
 * 这样"没装 Docker 也能把整个流程跑通看懂"，而且调用方完全不用关心用的是哪个。
 */
public interface CodeRunner {

    /**
     * 在沙箱里跑一段代码。
     *
     * @param jobId  本次执行 id，会写进容器标签，出问题时可以用它去捞容器
     * @param spec   语言配方
     * @param source 要写进容器的全部文件（含可选的标准输入），已校验过
     */
    ExecutionResponse run(String jobId, LanguageSpec spec, SourceBundle source);
}
