package com.chenchen.codeexecutor.language;

/**
 * 文件名的校验规则。
 *
 * <p>抽成一个类是因为这条规则有<b>两个地方</b>要用，而它们必须完全一致：
 * <ul>
 *   <li>{@link LanguageRegistry} 在启动时校验语言配置里的 {@code source-file-name}</li>
 *   <li>执行时校验用户提交的附加文件名</li>
 * </ul>
 * 如果两处规则不一致，就会出现"配置能过、请求被拒"或者更糟的"请求能过、路径穿越成功"。
 *
 * <p>规则本身很窄，因为文件名最终会被拼进容器内的路径：
 * <b>只允许不含任何路径成分的纯文件名</b>。
 */
public final class FileNames {

    /** 文件名长度上限，纯属防呆。 */
    public static final int MAX_LENGTH = 64;

    private FileNames() {
    }

    /**
     * 是不是一个合法的纯文件名。
     *
     * <p>拒绝的情况：
     * <ul>
     *   <li>空 / 全空白</li>
     *   <li>含 {@code /} 或 {@code \} —— 路径分隔符</li>
     *   <li>含 {@code ..} —— 目录穿越</li>
     *   <li>以 {@code .} 开头 —— 隐藏文件，也避免和内部的 {@code .stdin} 撞名</li>
     *   <li>超长</li>
     * </ul>
     */
    public static boolean isValid(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.length() > MAX_LENGTH) {
            return false;
        }
        if (name.contains("/") || name.contains("\\")) {
            return false;
        }
        if (name.contains("..")) {
            return false;
        }
        return !name.startsWith(".");
    }

    /** 给用户看的规则说明，用在报错信息里。 */
    public static String rule() {
        return "必须是不含路径的纯文件名（不能含 / \\ ..，不能以 . 开头，长度不超过 "
                + MAX_LENGTH + "）";
    }
}
