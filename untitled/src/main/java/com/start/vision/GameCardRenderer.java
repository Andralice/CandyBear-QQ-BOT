package com.start.vision;

import com.start.model.KkrbGameData;
import com.start.model.KkrbGameData.DoorPassword;
import com.start.model.KkrbGameData.SwatProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

/**
 * 三角洲行动游戏数据卡片渲染器。
 * 为特勤处（产品利润）、密码（地图密码门）等设计专用卡片布局。
 */
public class GameCardRenderer {

    private static final Logger logger = LoggerFactory.getLogger(GameCardRenderer.class);

    private static final int W = 640;
    private static final int PAD = 20;

    // 配色 — 柔和暖调
    private static final Color BG        = new Color(0xFFF8F0);
    private static final Color HEADER_BG = new Color(0xFFF0E0);
    private static final Color ROW_BG    = new Color(0xFFF5EE);
    private static final Color ROW_ALT   = new Color(0xFFF0E8);
    private static final Color TEXT      = new Color(0x5C4033);
    private static final Color SUBTEXT   = new Color(0x9B8E82);
    private static final Color BG_PILL   = new Color(0xFFF3D0);
    private static final Color ACCENT    = new Color(0xF0A500);
    private static final Color GREEN     = new Color(0x3CB371);
    private static final Color BLUE      = new Color(0x5BA0D0);
    private static final Color PURPLE    = new Color(0x9B7EC4);

    private final Font fTitle, fBody, fSmall, fMono;

    public GameCardRenderer() {
        fTitle = load("HarmonyOS_SansSC_Bold.ttf",    18f);
        fBody  = load("HarmonyOS_SansSC_Medium.ttf",  14f);
        fSmall = load("HarmonyOS_SansSC_Regular.ttf", 11f);
        fMono  = load("HarmonyOS_SansSC_Medium.ttf",  16f);
    }

    private Font load(String name, float sz) {
        try {
            java.io.InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("assets/fonts/" + name);
            if (is == null) return new Font(Font.SANS_SERIF, Font.PLAIN, (int) sz);
            return Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(sz);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, (int) sz);
        }
    }

    /** 渲染数据为 base64 PNG */
    public String renderToBase64(KkrbGameData data) {
        try {
            if (data == null) return null;

            if (data.products != null && !data.products.isEmpty())
                return renderSwat(data);
            if (data.passwords != null && !data.passwords.isEmpty())
                return renderPasswords(data);
            if (data.text != null && !data.text.isEmpty())
                return null; // 纯文本不需要渲染

            return null;
        } catch (Exception e) {
            logger.error("渲染游戏数据卡片失败: {}", e.toString(), e);
            return null;
        }
    }

    // ======== 特勤处 ========

    private String renderSwat(KkrbGameData data) {
        List<SwatProduct> products = data.products;
        int ROW_H = 64;
        int GAP = 6;
        int HEADER_H = 60;
        int H = PAD + HEADER_H + GAP + products.size() * (ROW_H + GAP) + PAD;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        configure(g);

        g.setColor(BG);
        g.fillRoundRect(0, 0, W, H, 20, 20);

        int y = PAD;
        y = drawSwatHeader(g, data, y);
        y += GAP;

        for (int i = 0; i < products.size(); i++) {
            y = drawSwatRow(g, products.get(i), y, i, ROW_H, GAP);
        }

        g.dispose();
        return toBase64(img);
    }

    private int drawSwatHeader(Graphics2D g, KkrbGameData data, int y) {
        int w = W - 2 * PAD;
        g.setColor(HEADER_BG);
        g.fillRoundRect(PAD, y, w, 60, 14, 14);

        g.setFont(fTitle);
        g.setColor(TEXT);
        g.drawString("三角洲行动 · 特勤处", PAD + 14, y + 28);

        // 利润模式标签
        g.setFont(fSmall);
        g.setColor(ACCENT);
        g.drawString("时薪排行", PAD + 16, y + 48);

        if (data.timestamp != null) {
            g.setFont(fSmall);
            g.setColor(SUBTEXT);
            FontMetrics fm = g.getFontMetrics(fSmall);
            String ts = data.timestamp;
            g.drawString(ts, PAD + w - fm.stringWidth(ts) - 12, y + 28);
        }

        return y + 60;
    }

    private int drawSwatRow(Graphics2D g, SwatProduct p, int y, int idx, int h, int gap) {
        int x = PAD;
        int w = W - 2 * PAD;

        // 行背景
        g.setColor(idx % 2 == 0 ? ROW_BG : ROW_ALT);
        g.fillRoundRect(x, y, w, h, 10, 10);

        // 左侧颜色条
        g.setColor(workbenchColor(p.workbench));
        g.fillRoundRect(x, y + 8, 4, h - 16, 2, 2);

        int cx = x + 16;

        // 产品名（大字）
        g.setFont(fBody);
        g.setColor(TEXT);
        g.drawString(p.product, cx, y + 26);

        // 工作台标签
        g.setFont(fSmall);
        FontMetrics sfm = g.getFontMetrics(fSmall);
        String wb = p.workbench;
        int bw = sfm.stringWidth(wb) + 14;
        g.setColor(BG_PILL);
        g.fillRoundRect(cx, y + 34, bw, 20, 10, 10);
        g.setColor(workbenchColor(p.workbench));
        g.drawString(wb, cx + 7, y + 48);

        // 右侧数据 — 时薪 + 总利润 + 出售时间
        int rx = x + w - 12;
        g.setFont(fSmall);

        // 出售时间
        if (p.sellTime != null && !p.sellTime.isEmpty()) {
            String sell = p.sellTime.startsWith("卖") ? p.sellTime : "卖 " + p.sellTime;
            int sw = sfm.stringWidth(sell);
            rx -= sw + 4;
            g.setColor(SUBTEXT);
            g.drawString(sell, rx, y + 26);
        }

        // 理想售价
        String priceStr = fmtPrice(p.idealPrice);
        int pw = sfm.stringWidth(priceStr) + 4;
        rx -= pw;
        g.setColor(SUBTEXT);
        g.drawString(priceStr, rx, y + 26);

        // 时薪（醒目的绿色大字）
        g.setFont(fMono);
        FontMetrics mfm = g.getFontMetrics(fMono);
        String profitStr = fmtPrice(p.profit);
        int prw = mfm.stringWidth(profitStr) + 4;
        rx -= prw;
        g.setColor(GREEN);
        g.drawString(profitStr, rx, y + 28);
        g.setFont(fSmall);
        g.setColor(SUBTEXT);
        g.drawString("/h", rx + prw - 2, y + 24);

        return y + h + gap;
    }

    private Color workbenchColor(String wb) {
        if (wb == null) return ACCENT;
        return switch (wb) {
            case "工作台" -> ACCENT;
            case "防具台" -> BLUE;
            case "技术中心" -> PURPLE;
            case "制药台" -> GREEN;
            default -> ACCENT;
        };
    }

    // ======== 密码 ========

    private String renderPasswords(KkrbGameData data) {
        List<DoorPassword> passwords = data.passwords;
        int ROW_H = 52;
        int GAP = 4;
        int HEADER_H = 56;
        int H = PAD + HEADER_H + GAP + passwords.size() * (ROW_H + GAP) + PAD;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        configure(g);

        g.setColor(BG);
        g.fillRoundRect(0, 0, W, H, 20, 20);

        int y = PAD;
        int w = W - 2 * PAD;

        // Header
        g.setColor(HEADER_BG);
        g.fillRoundRect(PAD, y, w, HEADER_H, 14, 14);
        g.setFont(fTitle);
        g.setColor(TEXT);
        g.drawString("三角洲行动 · 地图密码", PAD + 14, y + 32);
        if (data.timestamp != null) {
            g.setFont(fSmall);
            g.setColor(SUBTEXT);
            FontMetrics fm = g.getFontMetrics(fSmall);
            g.drawString(data.timestamp, PAD + w - fm.stringWidth(data.timestamp) - 12, y + 32);
        }
        y += HEADER_H + GAP;

        // Password rows
        for (int i = 0; i < passwords.size(); i++) {
            DoorPassword dp = passwords.get(i);
            g.setColor(i % 2 == 0 ? ROW_BG : ROW_ALT);
            g.fillRoundRect(PAD, y, w, ROW_H, 10, 10);

            // 地图名
            g.setFont(fBody);
            g.setColor(TEXT);
            g.drawString(dp.map, PAD + 14, y + 28);

            // 日期
            g.setFont(fSmall);
            g.setColor(SUBTEXT);
            String date = dp.updateDate != null ? dp.updateDate : "";
            g.drawString(date, PAD + 14, y + 44);

            // 密码（等宽大字）
            if (dp.password != null) {
                g.setFont(fMono);
                FontMetrics mfm = g.getFontMetrics(fMono);
                int pwW = mfm.stringWidth(dp.password);
                int pwX = PAD + w - 20 - pwW;

                // 密码背景
                g.setColor(BG_PILL);
                g.fillRoundRect(pwX - 10, y + 8, pwW + 20, ROW_H - 16, 10, 10);
                g.setColor(ACCENT);
                g.drawString(dp.password, pwX, y + 32);
            }

            y += ROW_H + GAP;
        }

        g.dispose();
        return toBase64(img);
    }

    // ======== helpers ========

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    }

    private String toBase64(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private String fmtPrice(int n) {
        if (n >= 10000) {
            double wan = n / 10000.0;
            if (wan == (int) wan) return (int) wan + "w";
            return String.format("%.1fw", wan);
        }
        if (n >= 1000) return (n / 1000) + "," + (n % 1000);
        return String.valueOf(n);
    }
}
