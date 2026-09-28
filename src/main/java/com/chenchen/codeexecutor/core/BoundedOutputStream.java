package com.chenchen.codeexecutor.core;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 有上限的输出流：超过上限的内容直接丢掉，但要记住"丢过东西"。
 *
 * <p>为什么必须有这个？因为 {@code while(true) System.out.println("x")} 是最容易写出来的
 * 一行攻击代码。没有上限，这些输出会全部堆进执行器的内存里，容器限制得再死也没用——
 * 被打爆的是执行器自己。
 *
 * <p>注意：超限之后我们仍然继续把流读干净（只是不存），
 * 否则写端会因为管道写满而阻塞，进程反而永远退不出来。
 */
public class BoundedOutputStream extends OutputStream {

    private final int maxBytes;
    private final ByteArrayOutputStream buffer;
    private boolean truncated;

    public BoundedOutputStream(int maxBytes) {
        this.maxBytes = Math.max(1, maxBytes);
        this.buffer = new ByteArrayOutputStream(Math.min(this.maxBytes, 8192));
    }

    @Override
    public synchronized void write(int b) {
        if (buffer.size() >= maxBytes) {
            truncated = true;
            return;
        }
        buffer.write(b);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        int room = maxBytes - buffer.size();
        if (room <= 0) {
            truncated = true;
            return;
        }
        if (length > room) {
            buffer.write(bytes, offset, room);
            truncated = true;
        } else {
            buffer.write(bytes, offset, length);
        }
    }

    public synchronized String text() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public synchronized boolean isTruncated() {
        return truncated;
    }
}
