package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import io.appium.java_client.AppiumDriver;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR 屏幕识别与点击支持。
 * <p>
 * 使用 Tesseract OCR 识别屏幕上的文字，并根据文字位置进行精准点击。
 * 相比固定坐标点击，OCR 方式更加智能和可靠，不受设备分辨率影响。
 * <p>
 * 关键设计：
 * 1. 截图 → OCR 识别 → 获取文字坐标 → 点击
 * 2. 支持中文识别（需要中文训练数据）
 * 3. 提供多种匹配策略：精确匹配、包含匹配、正则匹配
 */
public class OcrSupport implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(OcrSupport.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final GestureSupport gestureSupport;
    private final Tesseract tesseract;
    private final String adbPath;

    /** OCR 是否可用（初始化失败时降级） */
    private volatile boolean ocrAvailable = false;

    public OcrSupport(AppiumDriver driver, AutomationConfig config, GestureSupport gestureSupport) {
        this.driver = driver;
        this.config = config;
        this.gestureSupport = gestureSupport;
        this.adbPath = detectAdbPath();

        // 初始化 Tesseract
        this.tesseract = new Tesseract();
        initTesseract();

        log.info("[OcrSupport] OCR 支持已初始化，OCR 可用: {}", ocrAvailable);
    }

    /**
     * 初始化 Tesseract OCR 引擎。
     */
    private void initTesseract() {
        try {
            // 设置 tessdata 目录（包含训练数据）
            // 优先使用项目内的 tessdata，其次使用系统安装
            String tessDataPath = findTessDataPath();
            if (tessDataPath != null) {
                tesseract.setDatapath(tessDataPath);
                log.info("[OcrSupport] tessdata 路径: {}", tessDataPath);
            } else {
                log.warn("[OcrSupport] 未找到 tessdata 目录，OCR 功能不可用");
                return;
            }

            // 设置语言：中文简体 + 英文
            tesseract.setLanguage("chi_sim+eng");
            // 设置页面分割模式：自动检测
            tesseract.setPageSegMode(3);
            // 设置 OCR 引擎模式：LSTM + Legacy
            tesseract.setOcrEngineMode(3);

            ocrAvailable = true;
            log.info("[OcrSupport] Tesseract OCR 初始化成功");
        } catch (Exception e) {
            log.error("[OcrSupport] Tesseract OCR 初始化失败: {}", e.getMessage());
            ocrAvailable = false;
        }
    }

    /**
     * 查找 tessdata 目录。
     */
    private String findTessDataPath() {
        // 1. 项目内 tessdata
        String projectPath = "automation/tessdata";
        if (new File(projectPath).exists()) {
            return new File(projectPath).getAbsolutePath();
        }

        // 2. 用户目录下的 tessdata
        String userHome = System.getProperty("user.home");
        String userTessData = userHome + "/tessdata";
        if (new File(userTessData).exists()) {
            return userTessData;
        }

        // 3. 系统安装路径（Windows）
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            String sysTessData = programFiles + "/Tesseract-OCR/tessdata";
            if (new File(sysTessData).exists()) {
                return sysTessData;
            }
        }

        // 4. 环境变量 TESSDATA_PREFIX
        String envTessData = System.getenv("TESSDATA_PREFIX");
        if (envTessData != null && new File(envTessData).exists()) {
            return envTessData;
        }

        return null;
    }

    /**
     * 探测 adb 可执行文件路径。
     */
    private static String detectAdbPath() {
        String androidHome = System.getenv("ANDROID_HOME");
        if (androidHome != null && !androidHome.isEmpty()) {
            String candidate = androidHome + "\\platform-tools\\adb.exe";
            if (new File(candidate).exists()) return candidate;
        }
        for (String dir : System.getenv("Path").split(File.pathSeparator)) {
            String candidate = dir + "\\adb.exe";
            if (new File(candidate).exists()) return candidate;
        }
        return "adb";
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) {
            this.driver = newDriver;
            this.gestureSupport.refreshDriver(newDriver);
        }
    }

    /**
     * 检查 OCR 是否可用。
     */
    public boolean isOcrAvailable() {
        return ocrAvailable;
    }

    /**
     * 截图并识别屏幕上的所有文字及其位置。
     *
     * @return 识别到的文字列表，每个元素包含文字和坐标
     */
    public List<OcrResult> recognizeScreen() {
        if (!ocrAvailable) {
            log.warn("[OcrSupport] OCR 不可用，返回空结果");
            return new ArrayList<>();
        }

        try {
            // 1. 截图
            BufferedImage screenshot = captureScreen();
            if (screenshot == null) {
                log.error("[OcrSupport] 截图失败");
                return new ArrayList<>();
            }

            // 2. OCR 识别
            List<OcrResult> results = new ArrayList<>();
            List<Word> words = tesseract.getWords(screenshot, 3); // 3 = Word level

            for (Word word : words) {
                String text = word.getText().trim();
                if (!text.isEmpty()) {
                    java.awt.Rectangle rect = word.getBoundingBox();
                    int centerX = rect.x + rect.width / 2;
                    int centerY = rect.y + rect.height / 2;
                    results.add(new OcrResult(text, centerX, centerY,
                            rect.x, rect.y, rect.width, rect.height));
                }
            }

            log.info("[OcrSupport] 识别到 {} 个文字区域", results.size());
            return results;

        } catch (Exception e) {
            log.error("[OcrSupport] OCR 识别失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 在屏幕上查找指定文字并点击。
     *
     * @param targetText 要查找的文字
     * @return 是否找到并点击成功
     */
    public boolean findAndClick(String targetText) {
        return findAndClick(targetText, false);
    }

    /**
     * 在屏幕上查找指定文字并点击。
     *
     * @param targetText  要查找的文字
     * @param exactMatch  是否精确匹配（false 则为包含匹配）
     * @return 是否找到并点击成功
     */
    public boolean findAndClick(String targetText, boolean exactMatch) {
        OcrResult result = findText(targetText, exactMatch);
        if (result != null) {
            log.info("[OcrSupport] 找到文字 '{}' 在 ({}, {})", targetText, result.centerX, result.centerY);
            gestureSupport.tapAtPixel(result.centerX, result.centerY);
            return true;
        } else {
            log.warn("[OcrSupport] 未找到文字 '{}'", targetText);
            return false;
        }
    }

    /**
     * 在屏幕上查找指定文字。
     *
     * @param targetText 要查找的文字
     * @param exactMatch 是否精确匹配
     * @return 找到的第一个结果，未找到返回 null
     */
    public OcrResult findText(String targetText, boolean exactMatch) {
        if (targetText == null || targetText.isEmpty()) return null;

        List<OcrResult> results = recognizeScreen();
        for (OcrResult result : results) {
            boolean matched = exactMatch
                    ? result.text.equals(targetText)
                    : result.text.contains(targetText);
            if (matched) {
                return result;
            }
        }
        return null;
    }

    /**
     * 在屏幕上查找所有匹配指定文字的结果。
     *
     * @param targetText 要查找的文字
     * @param exactMatch 是否精确匹配
     * @return 所有匹配的结果
     */
    public List<OcrResult> findAllText(String targetText, boolean exactMatch) {
        if (targetText == null || targetText.isEmpty()) return new ArrayList<>();

        List<OcrResult> results = recognizeScreen();
        List<OcrResult> matched = new ArrayList<>();
        for (OcrResult result : results) {
            boolean isMatch = exactMatch
                    ? result.text.equals(targetText)
                    : result.text.contains(targetText);
            if (isMatch) {
                matched.add(result);
            }
        }
        return matched;
    }

    /**
     * 截图获取屏幕图像。
     */
    private BufferedImage captureScreen() {
        try {
            // 使用 adb 截图并读取
            String tempFile = "screenshot_" + System.currentTimeMillis() + ".png";
            ProcessBuilder pb = new ProcessBuilder(adbPath, "exec-out", "screencap", "-p");
            Process process = pb.start();

            BufferedImage image = ImageIO.read(process.getInputStream());
            int exitCode = process.waitFor();

            if (exitCode != 0 || image == null) {
                log.error("[OcrSupport] adb 截图失败，exitCode={}", exitCode);
                return null;
            }

            return image;
        } catch (Exception e) {
            log.error("[OcrSupport] 截图异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * OCR 识别结果。
     */
    public static class OcrResult {
        private final String text;
        private final int centerX;
        private final int centerY;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public OcrResult(String text, int centerX, int centerY, int x, int y, int width, int height) {
            this.text = text;
            this.centerX = centerX;
            this.centerY = centerY;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public String getText() { return text; }
        public int getCenterX() { return centerX; }
        public int getCenterY() { return centerY; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }

        @Override
        public String toString() {
            return "OcrResult{text='" + text + "', center=(" + centerX + "," + centerY + ")}";
        }
    }
}
