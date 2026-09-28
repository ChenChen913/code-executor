package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.language.FileNames;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次执行要写进容器工作目录的<b>全部文件</b>。
 *
 * <p>存在的意义是把"用户提交的零散输入"变成"一份校验过的、有序的、有大小账的文件清单"，
 * 之后的流程只需要面对这份清单，不用再关心它从哪来。
 *
 * <p>顺序是有意的：入口文件排第一，附加文件按提交顺序，标准输入文件排最后。
 * 定死顺序是为了让日志和演练模式的输出稳定可读。
 */
public final class SourceBundle {

    /**
     * 标准输入在容器里的文件名。
     *
     * <p>以 {@code .} 开头是刻意的：{@link FileNames#isValid} 拒绝以点开头的名字，
     * 所以用户<b>不可能</b>提交一个同名文件来覆盖它。
     */
    public static final String STDIN_FILE = ".stdin";

    private final Map<String, byte[]> files;

    private SourceBundle(Map<String, byte[]> files) {
        this.files = Collections.unmodifiableMap(files);
    }

    /** 有序的文件清单：文件名 → 内容。 */
    public Map<String, byte[]> files() {
        return files;
    }

    /** 是否带标准输入。 */
    public boolean hasStdin() {
        return files.containsKey(STDIN_FILE);
    }

    /** 源文件（不含标准输入）的总字节数。 */
    public long sourceBytes() {
        return files.entrySet().stream()
                .filter(e -> !STDIN_FILE.equals(e.getKey()))
                .mapToLong(e -> e.getValue().length)
                .sum();
    }

    /** 除标准输入外的文件个数。 */
    public int sourceFileCount() {
        return hasStdin() ? files.size() - 1 : files.size();
    }

    /**
     * 组装并校验。
     *
     * <p>所有校验都在这里做，而且<b>在创建容器之前</b>做——
     * 一个不合法的请求不该占用容器的创建时间，更不该有机会碰到 Docker。
     *
     * @param entryName      入口文件名（来自语言配置，已校验过）
     * @param entryContent   入口文件内容
     * @param extraFiles     用户提交的附加文件，文件名 → 内容；可以为 null
     * @param stdin          标准输入；可以为 null
     * @param maxFiles       文件数上限（含入口与标准输入）
     * @param maxSourceBytes 所有源文件合计的字节上限
     * @param maxStdinBytes  标准输入的字节上限
     */
    public static SourceBundle assemble(String entryName,
                                        byte[] entryContent,
                                        Map<String, String> extraFiles,
                                        String stdin,
                                        int maxFiles,
                                        long maxSourceBytes,
                                        long maxStdinBytes) {

        if (entryContent == null || entryContent.length == 0) {
            throw ApiException.badRequest("code 不能为空");
        }

        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put(entryName, entryContent);

        if (extraFiles != null) {
            for (Map.Entry<String, String> entry : extraFiles.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();

                if (!FileNames.isValid(name)) {
                    throw ApiException.badRequest("附加文件名「" + entry.getKey() + "」不合法：" + FileNames.rule());
                }
                if (name.equals(entryName)) {
                    throw ApiException.badRequest(
                            "附加文件里不能再出现入口文件「" + entryName + "」，请直接改 code 字段");
                }
                if (entry.getValue() == null) {
                    throw ApiException.badRequest("附加文件「" + name + "」的内容不能为 null");
                }
                result.put(name, entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }

        byte[] stdinBytes = null;
        if (stdin != null && !stdin.isEmpty()) {
            stdinBytes = stdin.getBytes(StandardCharsets.UTF_8);
            if (stdinBytes.length > maxStdinBytes) {
                throw ApiException.badRequest(
                        "stdin " + stdinBytes.length + " 字节，超过上限 " + maxStdinBytes + " 字节");
            }
            result.put(STDIN_FILE, stdinBytes);
        }

        if (result.size() > maxFiles) {
            throw ApiException.badRequest(
                    "文件数 " + result.size() + " 个，超过上限 " + maxFiles + " 个");
        }

        long sourceBytes = result.entrySet().stream()
                .filter(e -> !STDIN_FILE.equals(e.getKey()))
                .mapToLong(e -> e.getValue().length)
                .sum();
        if (sourceBytes > maxSourceBytes) {
            throw ApiException.badRequest(
                    "所有源文件合计 " + sourceBytes + " 字节，超过上限 " + maxSourceBytes + " 字节");
        }

        return new SourceBundle(result);
    }
}
