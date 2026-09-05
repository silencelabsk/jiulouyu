package com.fanqie.auto;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 测试夹具（fixture）加载工具。
 * <p>
 * 从 test classpath 的 {@code fixtures/} 目录读取录制的 UI 树 XML，
 * 供各离线单元测试回放。<b>必须以 UTF-8 读取</b>——XML 含大量中文文案，
 * 若用平台默认编码（Windows GBK）读入会产生乱码，导致 text 匹配全部失效。
 * <p>
 * 本工具不连接真机、不启动 Appium session，纯文件读取。
 */
public final class TestFixtures {

    /** 统一屏幕尺寸：1080x2340（主流华为/小米全面机分辨率），所有 fixture 的 bounds 均基于此坐标系 */
    public static final int SCREEN_WIDTH = 1080;
    public static final int SCREEN_HEIGHT = 2340;

    private TestFixtures() {
    }

    /**
     * 读取 fixtures 目录下的 XML 夹具为字符串。
     *
     * @param name 文件名（如 "bookshelf.xml"）
     * @return XML 原始字符串
     * @throws IllegalStateException 当夹具不存在或读取失败时
     */
    public static String load(String name) {
        String resource = "fixtures/" + name;
        try (InputStream is = TestFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("测试夹具不存在: " + resource);
            }
            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("读取测试夹具失败: " + resource, e);
        }
    }
}
