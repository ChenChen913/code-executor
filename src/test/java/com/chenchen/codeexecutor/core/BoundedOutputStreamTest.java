package com.chenchen.codeexecutor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出截断的测试。
 *
 * <p>这一条不是"锦上添花"的功能，而是防 {@code while(true) print()} 的最后一道闸门，
 * 所以必须有用例盯着。
 */
class BoundedOutputStreamTest {

    @Test
    @DisplayName("没超过上限时原样保留")
    void keepsEverythingUnderLimit() {
        BoundedOutputStream stream = new BoundedOutputStream(1024);
        stream.write("hello 世界".getBytes(StandardCharsets.UTF_8), 0, "hello 世界".getBytes(StandardCharsets.UTF_8).length);

        assertThat(stream.text()).isEqualTo("hello 世界");
        assertThat(stream.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("超过上限时只留前 N 字节，并标记已截断")
    void truncatesBeyondLimit() {
        BoundedOutputStream stream = new BoundedOutputStream(5);
        stream.write("abcdefghij".getBytes(StandardCharsets.UTF_8), 0, 10);

        assertThat(stream.text()).isEqualTo("abcde");
        assertThat(stream.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("逐字节写入也不会越界")
    void truncatesByteByByte() {
        BoundedOutputStream stream = new BoundedOutputStream(3);
        for (byte b : "abcdefg".getBytes(StandardCharsets.UTF_8)) {
            stream.write(b);
        }

        assertThat(stream.text()).isEqualTo("abc");
        assertThat(stream.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("截断之后继续写入不会撑大缓冲区，但也不会抛异常")
    void keepsDrainingAfterTruncation() {
        BoundedOutputStream stream = new BoundedOutputStream(4);
        stream.write("abcd".getBytes(StandardCharsets.UTF_8), 0, 4);
        stream.write("efgh".getBytes(StandardCharsets.UTF_8), 0, 4);

        assertThat(stream.text()).isEqualTo("abcd");
        assertThat(stream.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("正好写满上限时不算截断")
    void exactlyAtLimitIsNotTruncated() {
        BoundedOutputStream stream = new BoundedOutputStream(4);
        stream.write("abcd".getBytes(StandardCharsets.UTF_8), 0, 4);

        assertThat(stream.text()).isEqualTo("abcd");
        assertThat(stream.isTruncated()).isFalse();
    }
}
