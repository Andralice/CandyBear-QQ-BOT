package com.start.vision;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 职业卡渲染模板 — 暗色企业级布局，大字版。
 */
public class ProfessionCardTemplate implements ImageTemplate<ProfessionData> {

    private static final int W = 800;
    private static final int P = 36;
    private static final int R = 14;

    // ── 基础色 ──
    private static final Color BG        = new Color(0x0D0F14);
    private static final Color SURFACE   = new Color(0x161820);
    private static final Color BORDER    = new Color(0x282A34);
    private static final Color TEXT      = new Color(0xF2F2F5);
    private static final Color SUBTEXT   = new Color(0xB0B2BC);
    private static final Color BAR_BG    = new Color(0x2E303A);
    private static final Color TICK      = new Color(0x585A64);
    private static final Color GLOW_EPIC = new Color(180, 80, 255, 30);
    private static final Color GLOW_LEGEND = new Color(255, 80, 80, 30);

    // ── 位阶色 ──
    private static final Color T1 = new Color(150, 160, 180);
    private static final Color T2 = new Color(100, 180, 255);
    private static final Color T3 = new Color(0, 191, 255);
    private static final Color T4 = new Color(180, 80, 255);
    private static final Color T5 = new Color(255, 80, 80);

    // ── 字体 ──
    private final Font fLabel, fBody, fTitle, fHero, fNumber;

    public ProfessionCardTemplate() {
        fLabel  = load("HarmonyOS_SansSC_Bold.ttf",   14f);
        fBody   = load("HarmonyOS_SansSC_Medium.ttf", 17f);
        fTitle  = load("HarmonyOS_SansSC_Bold.ttf",   24f);
        fHero   = load("HarmonyOS_SansSC_Black.ttf",  56f);
        fNumber = load("HarmonyOS_SansSC_Bold.ttf",   34f);
    }

    private Font load(String name, float sz) {
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("assets/fonts/" + name);
            if (is == null) return new Font(Font.SANS_SERIF, Font.PLAIN, (int) sz);
            return Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(sz);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, (int) sz);
        }
    }

    // ============================================================
    @Override
    public BufferedImage render(Object data) {
        if (!(data instanceof ProfessionData p)) throw new IllegalArgumentException("数据格式错误");

        int headerH = 100;
        int coreH   = 180;
        int statsH  = 56;
        int powerH  = 72;
        int footerH = 44;
        int gap     = 24;
        int descH   = calcDescLines(p.description) * 30;

        int H = P + headerH + gap + coreH + gap + statsH + gap + powerH + gap + descH + 12 + footerH + P;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.setColor(BG);
        g.fillRoundRect(0, 0, W, H, R, R);

        int y = P;
        y = header(g, p, y);
        y += gap;
        y = coreCard(g, p, y);
        y += gap;
        y = statsRow(g, p, y);
        y += gap;
        y = powerBar(g, p, y);
        y += gap;
        footer(g, p, y, H);

        g.dispose();
        return img;
    }

    // ============================================================
    // Header — 标题 + 稀有度 + 脉系 + 变化趋势
    // ============================================================

    private int header(Graphics2D g, ProfessionData p, int y) {
        int rw = W - 2 * P;
        Color tc = tierColor(p.tier);

        // 标题
        g.setFont(fTitle);
        g.setColor(TEXT);
        g.drawString("天命职业鉴定", P, y + 30);

        // 脉系 pill
        if (p.professionPath != null && !p.professionPath.isEmpty()) {
            g.setFont(fLabel);
            FontMetrics fm = g.getFontMetrics();
            String pathLabel = p.professionPath + "脉";
            int pw = fm.stringWidth(pathLabel) + 16;
            g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 18));
            g.fillRoundRect(P + 180, y + 10, pw, 26, 13, 13);
            g.setColor(tc);
            g.drawString(pathLabel, P + 188, y + 28);
        }

        // 右上：稀有度 badge
        String rarity = p.rarity;
        g.setFont(fLabel);
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(rarity) + 20;
        int bx = P + rw - bw;
        drawPillStroke(g, bx, y + 8, bw, 28, tc);
        g.setColor(tc);
        g.drawString(rarity, bx + 10, y + 26);

        // 位阶名
        g.setFont(fBody);
        g.setColor(SUBTEXT);
        g.drawString(p.tierName, P, y + 62);

        // 变化趋势（位阶名右侧）
        if (p.changeDesc != null && !p.changeDesc.isEmpty()) {
            g.setFont(fBody);
            g.setColor(tc);
            g.drawString(p.changeDesc, P + 200, y + 62);
        }

        // 运势（右上 badge 下方）
        drawLuckBadge(g, tc, bx, bw, y + 40, p.todayLuck);

        // 分隔线
        int ly = y + 82;
        g.setColor(BORDER);
        g.drawLine(P, ly, P + rw, ly);

        return y + 100;
    }

    private void drawLuckBadge(Graphics2D g, Color tc, int rx, int rw, int y, int luck) {
        int w = 100;
        int h = 22;
        int x = rx + rw - w;

        // BG
        g.setColor(BAR_BG);
        g.fillRoundRect(x, y, w, h, 11, 11);

        // fill
        double ratio = Math.min(1.0, Math.max(0.02, luck / 100.0));
        int fill = (int) ((w - 4) * ratio);
        if (fill > 2) {
            Color lc = luck >= 80 ? new Color(0x4CAF50) : luck >= 50 ? new Color(0xFFC107) : new Color(0xF44336);
            g.setColor(lc);
            g.fillRoundRect(x + 2, y + 2, fill, h - 4, 9, 9);
        }

        // text
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(TEXT);
        String s = "运势 " + luck;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, x + (w - fm.stringWidth(s)) / 2, y + 15);
    }

    // ============================================================
    // Core card — 大位阶数字 + 星 + 职业名 + 战力
    // ============================================================

    private int coreCard(Graphics2D g, ProfessionData p, int y) {
        int cw = W - 2 * P;
        int ch = 180;
        Color tc = tierColor(p.tier);

        // 稀有度光环
        if ("史诗".equals(p.rarity) || "传说".equals(p.rarity)) {
            g.setColor("史诗".equals(p.rarity) ? GLOW_EPIC : GLOW_LEGEND);
            g.setStroke(new BasicStroke(4f));
            g.drawRoundRect(P - 2, y - 2, cw + 4, ch + 4, R + 2, R + 2);
        }

        // 面板
        g.setColor(SURFACE);
        g.fillRoundRect(P, y, cw, ch, R, R);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(P, y, cw, ch, R, R);

        // ── 左列 ──
        int leftW = 140;
        // 位阶数字
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        g.setColor(tc);
        String tierStr = String.valueOf(p.tier);
        FontMetrics tfm = g.getFontMetrics();
        g.drawString(tierStr, P + (leftW - tfm.stringWidth(tierStr)) / 2, y + 88);

        // 星
        g.setFont(fLabel);
        int starY = y + 110;
        int starStart = P + (leftW - 5 * 20) / 2;
        for (int i = 0; i < 5; i++) {
            g.setColor(i < p.tier ? tc : TICK);
            g.drawString("★", starStart + i * 22, starY);
        }

        // 连击
        if (p.streakGood >= 3 || p.streakBad >= 3) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            boolean good = p.streakGood >= 3;
            g.setColor(good ? new Color(0xFF9800) : TICK);
            String icon = good ? "🔥" + p.streakGood + "连升" : "💀" + p.streakBad + "连降";
            FontMetrics ifm = g.getFontMetrics();
            g.drawString(icon, P + (leftW - ifm.stringWidth(icon)) / 2, y + 138);
        }

        // 竖线
        int sepX = P + leftW;
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(sepX, y + 22, sepX, y + ch - 22);

        // ── 右列 ──
        int rx = sepX + 28;

        // 职业名
        g.setFont(fHero);
        g.setColor(TEXT);
        g.drawString(p.professionName, rx, y + 68);

        // COMBAT POWER
        g.setFont(fLabel);
        g.setColor(SUBTEXT);
        g.drawString("COMBAT POWER", rx + 2, y + 94);

        // 战力数值
        g.setFont(fNumber);
        g.setColor(tc);
        g.drawString(formatNumber(p.combatPower), rx + 2, y + 130);

        // 右上 pill
        String pill = "第 " + p.tier + " 阶";
        g.setFont(fLabel);
        FontMetrics pfm = g.getFontMetrics();
        int pw = pfm.stringWidth(pill) + 18;
        int px = P + cw - pw - 18;
        drawPillFilled(g, px, y + 16, pw, 28, tc);
        g.setColor(tc);
        g.drawString(pill, px + 9, y + 34);

        return y + ch;
    }

    // ============================================================
    // Stats row — 排名 + 历史巅峰
    // ============================================================

    private int statsRow(Graphics2D g, ProfessionData p, int y) {
        // 左侧标签
        g.setFont(fLabel);
        g.setColor(SUBTEXT);
        g.drawString("排名与成就", P, y + 22);

        // 右侧内容
        g.setFont(fBody);
        int items = 0;
        int curX = P + 140;

        if (p.groupRank > 0) {
            String rank = "本群第 " + p.groupRank + " 名";
            g.setColor(TEXT);
            g.drawString(rank, curX, y + 22);
            curX += g.getFontMetrics().stringWidth(rank) + 28;
            items++;
        }

        if (p.bestTier > 0) {
            g.setColor(BORDER);
            g.drawLine(curX - 14, y + 4, curX - 14, y + 20); // 竖线分隔
            String best = "历史巅峰：" + bestTierLabel(p.bestTier);
            g.setColor(TEXT);
            g.drawString(best, curX, y + 22);
            items++;
        }

        if (items == 0) {
            g.setColor(TICK);
            g.drawString("暂无数据", curX, y + 22);
        }

        return y + 56;
    }

    // ============================================================
    // Power bar
    // ============================================================

    private int powerBar(Graphics2D g, ProfessionData p, int y) {
        int bw = W - 2 * P;
        int barH = 8;
        Color tc = tierColor(p.tier);

        // 标签 + 数值
        g.setFont(fLabel);
        g.setColor(SUBTEXT);
        g.drawString("战力评估", P, y + 16);

        String val = formatNumber(p.combatPower);
        g.setFont(fNumber);
        g.setColor(tc);
        FontMetrics vfm = g.getFontMetrics();
        g.drawString(val, P + bw - vfm.stringWidth(val), y + 22);

        // 进度条
        int barY = y + 38;
        g.setColor(BAR_BG);
        g.fillRoundRect(P, barY, bw, barH, 4, 4);

        double ratio = Math.min(1.0, Math.max(0.02, (double) p.combatPower / 10000.0));
        int fillW = (int) (bw * ratio);
        if (fillW > 3) {
            GradientPaint grad = new GradientPaint(P, barY, tc, P + fillW, barY, brighter(tc, 0.5f));
            g.setPaint(grad);
            g.fillRoundRect(P, barY, fillW, barH, 4, 4);
        }

        // 刻度
        g.setColor(TICK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int m : new int[]{2000, 4000, 6000, 8000}) {
            int tx = P + bw * m / 10000;
            g.drawLine(tx, barY + barH + 6, tx, barY + barH + 12);
            String label = (m / 1000) + "k";
            g.drawString(label, tx - g.getFontMetrics().stringWidth(label) / 2, barY + barH + 26);
        }
        g.drawString("10k", P + bw - g.getFontMetrics().stringWidth("10k") / 2, barY + barH + 26);

        return y + 72;
    }

    // ============================================================
    // Footer
    // ============================================================

    private void footer(Graphics2D g, ProfessionData p, int y, int H) {
        int rw = W - 2 * P;

        // 描述
        g.setFont(fBody);
        g.setColor(SUBTEXT);
        String desc = p.description != null ? p.description : "";
        drawWrapped(g, desc, P, y, rw, 30);

        // 底部
        int fy = H - P - 30;
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(P, fy, P + rw, fy);

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g.setFont(f);
        FontMetrics ffm = g.getFontMetrics();
        g.setColor(TICK);
        g.drawString("USER #" + p.userId, P, fy + 18);
        g.drawString(today, P + rw - ffm.stringWidth(today), fy + 18);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private int calcDescLines(String desc) {
        if (desc == null || desc.isEmpty()) return 1;
        int maxChars = (W - 2 * P) / 18;
        int lines = 0, cur = 0;
        for (int i = 0; i < desc.length(); i++) {
            cur++;
            if (desc.charAt(i) == '。' || desc.charAt(i) == '，' || desc.charAt(i) == '、'
                    || desc.charAt(i) == '；' || cur >= maxChars) { lines++; cur = 0; }
        }
        if (cur > 0) lines++;
        return Math.max(1, lines);
    }

    private void drawWrapped(Graphics2D g, String text, int x, int y, int maxW, int lineH) {
        if (text == null || text.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int cy = y;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String trial = line.toString() + ch;
            if (fm.stringWidth(trial) > maxW && line.length() > 0) {
                g.drawString(line.toString(), x, cy + lineH); cy += lineH;
                line = new StringBuilder(String.valueOf(ch));
            } else {
                line.append(ch);
            }
            if (ch == '。' || ch == '，' || ch == '、' || ch == '；') {
                g.drawString(line.toString(), x, cy + lineH); cy += lineH;
                line = new StringBuilder();
            }
        }
        if (line.length() > 0) g.drawString(line.toString(), x, cy + lineH);
    }

    private void drawPillStroke(Graphics2D g, int x, int y, int w, int h, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, y, w, h, h / 2, h / 2);
    }

    private void drawPillFilled(Graphics2D g, int x, int y, int w, int h, Color c) {
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 18));
        g.fillRoundRect(x, y, w, h, h / 2, h / 2);
        g.setColor(c);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, w, h, h / 2, h / 2);
    }

    private Color tierColor(int tier) {
        return switch (tier) {
            case 5 -> T5; case 4 -> T4; case 3 -> T3; case 2 -> T2;
            default -> T1;
        };
    }

    private static String bestTierLabel(int tier) {
        return switch (tier) {
            case 5 -> "五阶·登峰造极"; case 4 -> "四阶·炉火纯青";
            case 3 -> "三阶·融会贯通"; case 2 -> "二阶·登堂入室";
            default -> "一阶·初窥门径";
        };
    }

    private static String formatNumber(int n) {
        if (n >= 10000) return String.format("%.1fw", n / 1000.0 / 10);
        return String.valueOf(n);
    }

    private static Color brighter(Color c, float f) {
        return new Color(
                Math.min(255, (int)(c.getRed() + (255 - c.getRed()) * f)),
                Math.min(255, (int)(c.getGreen() + (255 - c.getGreen()) * f)),
                Math.min(255, (int)(c.getBlue() + (255 - c.getBlue()) * f)));
    }
}
